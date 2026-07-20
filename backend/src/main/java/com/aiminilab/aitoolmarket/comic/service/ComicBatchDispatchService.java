package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicGenerationBatch;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.entity.ComicShotAttempt;
import com.aiminilab.aitoolmarket.comic.mapper.ComicGenerationBatchMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotAttemptMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final WorkflowRunApplicationService workflowRunApplicationService;
    private final ComicGenerationBatchService batchService;
    private final ComicProjectService projectService;

    public ComicBatchDispatchService(ComicGenerationBatchMapper batchMapper,
                                     ComicShotAttemptMapper attemptMapper,
                                     ComicShotMapper shotMapper,
                                     WorkflowRunMapper workflowRunMapper,
                                     WorkflowRunApplicationService workflowRunApplicationService,
                                     ComicGenerationBatchService batchService,
                                     ComicProjectService projectService) {
        this.batchMapper = batchMapper;
        this.attemptMapper = attemptMapper;
        this.shotMapper = shotMapper;
        this.workflowRunMapper = workflowRunMapper;
        this.workflowRunApplicationService = workflowRunApplicationService;
        this.batchService = batchService;
        this.projectService = projectService;
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
        List<ComicShotAttempt> attempts = attemptMapper.selectByBatch(batch.getId());
        long active = attempts.stream().filter(attempt -> isActiveAttempt(attempt.getStatus())).count();
        int available = Math.max(0, batch.getMaxParallelism() - (int) active);
        if (available == 0) return;

        int dispatched = 0;
        for (ComicShotAttempt attempt : attempts) {
            if (dispatched >= available) break;
            if (!"PENDING".equals(attempt.getStatus()) || !dependencyReady(attempt.getShotId())) continue;
            if (attemptMapper.claimForDispatch(attempt.getId()) != 1) continue;
            try {
                ObjectNode input = projectService.buildShotOperationInput(
                        batch.getUserId(), batch.getProjectId(), batch.getEpisodeId(), attempt.getShotId()
                );
                input.put("comicGenerationBatchId", batch.getId());
                input.put("comicShotAttemptId", attempt.getId());
                WorkflowRunCreated created = workflowRunApplicationService.create(new CreateWorkflowRunCommand(
                        batch.getUserId(), batch.getToolCode(), input, attempt.getIdempotencyKey(),
                        "COMIC_PROJECT", null
                ));
                String status = normalizeRunStatus(created.status());
                if (attemptMapper.bindWorkflow(
                        attempt.getId(), created.runId(), created.rootTaskId(), status) != 1) {
                    throw new IllegalStateException("Comic attempt lost dispatch ownership");
                }
                dispatched++;
            } catch (BusinessException exception) {
                attemptMapper.syncState(
                        attempt.getId(), "FAILED", null, exception.getErrorCode().name(),
                        limit(exception.getMessage(), 1900), LocalDateTime.now()
                );
            } catch (Exception exception) {
                attemptMapper.syncState(
                        attempt.getId(), "FAILED", null, "DISPATCH_FAILED",
                        limit(exception.getMessage(), 1900), LocalDateTime.now()
                );
            }
        }
        batchMapper.updateState(batch.getId(), "RUNNING", LocalDateTime.now(), null);
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
        boolean hasActive = attempts.stream().anyMatch(a -> isActiveAttempt(a.getStatus()));
        if (hasActive) return;
        for (ComicShotAttempt attempt : attempts) {
            if (!"PENDING".equals(attempt.getStatus())) continue;
            ComicShot shot = shotMapper.selectById(attempt.getShotId());
            if (shot == null || shot.getDependsOnShotId() == null) continue;
            ComicShot dependency = shotMapper.selectById(shot.getDependsOnShotId());
            if (dependency != null && dependency.getSelectedAttemptId() != null) continue;
            boolean dependencyFailed = attempts.stream()
                    .filter(item -> item.getShotId().equals(shot.getDependsOnShotId()))
                    .anyMatch(item -> isFailed(item.getStatus()));
            if (dependencyFailed) {
                attemptMapper.syncState(
                        attempt.getId(), "FAILED", null, "DEPENDENCY_FAILED",
                        "前置连续镜头生成失败", LocalDateTime.now()
                );
            }
        }
    }

    private boolean dependencyReady(Long shotId) {
        ComicShot shot = shotMapper.selectById(shotId);
        if (shot == null) return false;
        if (shot.getDependsOnShotId() == null) return true;
        ComicShot dependency = shotMapper.selectById(shot.getDependsOnShotId());
        return dependency != null && dependency.getSelectedAttemptId() != null;
    }

    private boolean isActiveAttempt(String status) {
        return Set.of("DISPATCHING", "RUNNING", "AWAITING_USER", "AWAITING_FUNDS", "CANCELLING")
                .contains(status);
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

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
