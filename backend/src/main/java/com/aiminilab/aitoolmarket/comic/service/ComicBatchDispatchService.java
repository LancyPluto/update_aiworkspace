package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicGenerationBatch;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.entity.ComicShotAttempt;
import com.aiminilab.aitoolmarket.comic.mapper.ComicGenerationBatchMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotAttemptMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class ComicBatchDispatchService {
    private static final Logger LOG = LoggerFactory.getLogger(ComicBatchDispatchService.class);
    private static final Set<String> ACTIVE_RUN_STATUSES = Set.of(
            "RUNNING", "AWAITING_USER", "AWAITING_FUNDS", "CANCELLING"
    );
    private static final Set<String> FAILED_RUN_STATUSES = Set.of("FAILED", "TIMEOUT", "CANCELLED");

    private final ComicGenerationBatchMapper batchMapper;
    private final ComicShotAttemptMapper attemptMapper;
    private final ComicShotMapper shotMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final ComicGenerationBatchService batchService;
    private final ComicBatchAttemptLaunchService attemptLaunchService;

    public ComicBatchDispatchService(ComicGenerationBatchMapper batchMapper,
                                     ComicShotAttemptMapper attemptMapper,
                                     ComicShotMapper shotMapper,
                                     WorkflowRunMapper workflowRunMapper,
                                     ComicGenerationBatchService batchService,
                                     ComicBatchAttemptLaunchService attemptLaunchService) {
        this.batchMapper = batchMapper;
        this.attemptMapper = attemptMapper;
        this.shotMapper = shotMapper;
        this.workflowRunMapper = workflowRunMapper;
        this.batchService = batchService;
        this.attemptLaunchService = attemptLaunchService;
    }

    public ComicDtos.BatchDetail dispatch(Long userId, Long projectId, Long episodeId, Long batchId) {
        ComicGenerationBatch batch = batchService.requireOwned(userId, projectId, episodeId, batchId);
        dispatchBatch(batch);
        return batchService.detail(batchMapper.selectById(batchId));
    }

    public ComicDtos.BatchDetail refresh(Long userId, Long projectId, Long episodeId, Long batchId) {
        ComicGenerationBatch batch = batchService.requireOwned(userId, projectId, episodeId, batchId);
        reconcile(batch);
        return batchService.detail(batchMapper.selectById(batchId));
    }

    public ComicDtos.BatchDetail refreshLatest(Long userId, Long projectId, Long episodeId) {
        ComicGenerationBatch batch = batchService.requireLatest(userId, projectId, episodeId);
        reconcile(batch);
        return batchService.detail(batchMapper.selectById(batch.getId()));
    }

    @Scheduled(fixedDelayString = "${comic.batch.dispatch-interval-ms:5000}")
    public void dispatchPendingBatches() {
        for (Long batchId : batchMapper.selectDispatchableIds(20)) {
            try {
                ComicGenerationBatch batch = batchMapper.selectById(batchId);
                if (batch != null) dispatchBatch(batch);
            } catch (Exception exception) {
                LOG.warn("Comic batch dispatch failed batchId={}", batchId, exception);
            }
        }
    }

    void dispatchBatch(ComicGenerationBatch batch) {
        reconcile(batch);
        batch = batchMapper.selectById(batch.getId());
        if (batch == null || !Set.of("CREATED", "RUNNING").contains(batch.getStatus())) return;
        int launchBudget = Math.max(1, attemptMapper.selectByBatch(batch.getId()).size());
        for (int index = 0; index < launchBudget; index++) {
            try {
                ComicBatchAttemptLaunchService.LaunchResult result = attemptLaunchService.launchNext(batch.getId());
                if (result == ComicBatchAttemptLaunchService.LaunchResult.LAUNCHED
                        || result == ComicBatchAttemptLaunchService.LaunchResult.RETRY) {
                    continue;
                }
                break;
            } catch (ComicBatchAttemptLaunchService.AttemptLaunchException failure) {
                attemptLaunchService.failAfterRollback(failure);
            }
        }
        batchMapper.markRunning(batch.getId(), LocalDateTime.now());
        reconcile(batchMapper.selectById(batch.getId()));
    }

    void reconcile(ComicGenerationBatch batch) {
        if (batch == null) return;
        List<ComicShotAttempt> attempts = attemptMapper.selectByBatch(batch.getId());
        for (ComicShotAttempt attempt : attempts) {
            if (attempt.getWorkflowRunId() == null) continue;
            WorkflowRun run = workflowRunMapper.selectById(attempt.getWorkflowRunId());
            if (run == null) continue;
            String next = normalizeRunStatus(run.getStatus());
            if (!next.equals(attempt.getStatus()) || isTerminal(next)) {
                String result = "SUCCESS".equals(next) ? run.getContextJson() : null;
                String errorCode = FAILED_RUN_STATUSES.contains(run.getStatus()) ? run.getStatus() : null;
                attemptMapper.syncState(
                        attempt.getId(), next, result, errorCode, run.getErrorMessage(),
                        isTerminal(next) ? defaultFinishedAt(run.getFinishedAt()) : null
                );
                if ("SUCCESS".equals(next)) {
                    shotMapper.selectFirstSuccessfulAttempt(attempt.getShotId(), attempt.getId());
                }
            }
        }
        attempts = attemptMapper.selectByBatch(batch.getId());
        failBrokenDependencies(attempts);
        attempts = attemptMapper.selectByBatch(batch.getId());
        long success = attempts.stream().filter(a -> "SUCCESS".equals(a.getStatus())).count();
        long failed = attempts.stream().filter(a -> isFailed(a.getStatus())).count();
        long pendingOrActive = attempts.size() - success - failed;
        if (attempts.isEmpty()) {
            batchMapper.updateState(batch.getId(), "FAILED", batch.getStartedAt(), LocalDateTime.now());
        } else if (pendingOrActive == 0) {
            String status = success == attempts.size() ? "SUCCESS" : success == 0 ? "FAILED" : "PARTIAL_FAILED";
            batchMapper.updateState(batch.getId(), status, batch.getStartedAt(), LocalDateTime.now());
        }
    }

    private void failBrokenDependencies(List<ComicShotAttempt> attempts) {
        for (ComicShotAttempt attempt : attempts) {
            if (!"PENDING".equals(attempt.getStatus())) continue;
            ComicShot shot = shotMapper.selectById(attempt.getShotId());
            if (shot == null) {
                attemptMapper.syncState(
                        attempt.getId(), "FAILED", null, "SHOT_NOT_FOUND",
                        "分镜已不存在", LocalDateTime.now()
                );
                continue;
            }
            if (shot.getDependsOnShotId() == null) continue;
            ComicShot dependency = shotMapper.selectById(shot.getDependsOnShotId());
            if (dependency != null && dependency.getSelectedAttemptId() != null) continue;
            if (attemptMapper.countActiveByShot(shot.getDependsOnShotId()) > 0) continue;
            attemptMapper.syncState(
                    attempt.getId(), "FAILED", null, "DEPENDENCY_FAILED",
                    "前置连续镜头没有可用版本", LocalDateTime.now()
            );
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || isFailed(status);
    }

    private boolean isFailed(String status) {
        return Set.of("FAILED", "TIMEOUT", "CANCELLED").contains(status);
    }

    private String normalizeRunStatus(String status) {
        if (status == null) return "RUNNING";
        if ("SUCCESS".equals(status)) return "SUCCESS";
        if (FAILED_RUN_STATUSES.contains(status)) return status;
        if (ACTIVE_RUN_STATUSES.contains(status)) return status;
        return "RUNNING";
    }

    private LocalDateTime defaultFinishedAt(LocalDateTime value) {
        return value == null ? LocalDateTime.now() : value;
    }

}
