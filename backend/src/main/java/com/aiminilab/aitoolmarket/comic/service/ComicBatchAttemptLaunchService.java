package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.entity.ComicGenerationBatch;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.entity.ComicShotAttempt;
import com.aiminilab.aitoolmarket.comic.mapper.ComicGenerationBatchMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotAttemptMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class ComicBatchAttemptLaunchService {
    private static final Set<String> ACTIVE_BATCH_STATUSES = Set.of("CREATED", "RUNNING");
    private static final Set<String> ACTIVE_ATTEMPT_STATUSES = Set.of(
            "DISPATCHING", "RUNNING", "AWAITING_USER", "AWAITING_FUNDS", "CANCELLING"
    );
    private static final Set<String> ACTIVE_RUN_STATUSES = Set.of(
            "RUNNING", "AWAITING_USER", "AWAITING_FUNDS", "CANCELLING"
    );
    private static final Set<String> FAILED_RUN_STATUSES = Set.of("FAILED", "TIMEOUT", "CANCELLED");

    private final ComicGenerationBatchMapper batchMapper;
    private final ComicShotAttemptMapper attemptMapper;
    private final ComicShotMapper shotMapper;
    private final ComicProjectService projectService;
    private final WorkflowRunApplicationService workflowRunApplicationService;

    public ComicBatchAttemptLaunchService(ComicGenerationBatchMapper batchMapper,
                                          ComicShotAttemptMapper attemptMapper,
                                          ComicShotMapper shotMapper,
                                          ComicProjectService projectService,
                                          WorkflowRunApplicationService workflowRunApplicationService) {
        this.batchMapper = batchMapper;
        this.attemptMapper = attemptMapper;
        this.shotMapper = shotMapper;
        this.projectService = projectService;
        this.workflowRunApplicationService = workflowRunApplicationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LaunchResult launchNext(Long batchId) {
        ComicGenerationBatch batch = batchMapper.selectByIdForUpdate(batchId);
        if (batch == null || !ACTIVE_BATCH_STATUSES.contains(batch.getStatus())) {
            return LaunchResult.BATCH_INACTIVE;
        }

        List<ComicShotAttempt> attempts = attemptMapper.selectByBatchForUpdate(batchId);
        long active = attempts.stream()
                .filter(attempt -> ACTIVE_ATTEMPT_STATUSES.contains(attempt.getStatus()))
                .count();
        if (active >= batch.getMaxParallelism()) {
            return LaunchResult.NO_CAPACITY;
        }

        ComicShotAttempt attempt = firstReadyAttempt(attempts);
        if (attempt == null) {
            return LaunchResult.NO_READY_ATTEMPT;
        }
        if (attemptMapper.claimForDispatch(attempt.getId()) != 1) {
            return LaunchResult.RETRY;
        }

        try {
            ObjectNode input = projectService.buildShotOperationInput(
                    batch.getUserId(), batch.getProjectId(), batch.getEpisodeId(), attempt.getShotId()
            );
            input.put("comicGenerationBatchId", batch.getId());
            input.put("comicShotAttemptId", attempt.getId());
            String versionPrefix = "shot-%d-attempt-%d".formatted(attempt.getShotId(), attempt.getId());
            input.put("keyframeVersionId", versionPrefix + ":keyframe");
            input.put("audioVersionId", versionPrefix + ":audio");
            input.put("clipVersionId", versionPrefix + ":clip");
            WorkflowRunCreated created = workflowRunApplicationService.createInCurrentTransaction(
                    new CreateWorkflowRunCommand(
                            batch.getUserId(), batch.getToolCode(), input, attempt.getIdempotencyKey(),
                            "COMIC_PROJECT", null
                    )
            );
            String status = normalizeRunStatus(created.status());
            if (attemptMapper.bindWorkflow(
                    attempt.getId(), created.runId(), created.rootTaskId(), status
            ) != 1) {
                throw new IllegalStateException("Comic attempt lost dispatch ownership");
            }
            return LaunchResult.LAUNCHED;
        } catch (RuntimeException exception) {
            String errorCode = exception instanceof BusinessException businessException
                    ? businessException.getErrorCode().name()
                    : "DISPATCH_FAILED";
            throw new AttemptLaunchException(
                    attempt.getId(), errorCode, limit(exception.getMessage(), 1900), exception
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failAfterRollback(AttemptLaunchException failure) {
        return attemptMapper.failPendingLaunch(
                failure.attemptId(), failure.errorCode(), failure.errorMessage(), LocalDateTime.now()
        ) == 1;
    }

    private ComicShotAttempt firstReadyAttempt(List<ComicShotAttempt> attempts) {
        for (ComicShotAttempt attempt : attempts) {
            if (!"PENDING".equals(attempt.getStatus())) {
                continue;
            }
            ComicShot shot = shotMapper.selectById(attempt.getShotId());
            if (shot == null) {
                throw new AttemptLaunchException(
                        attempt.getId(), "SHOT_NOT_FOUND", "Comic shot no longer exists", null
                );
            }
            if (shot.getDependsOnShotId() == null) {
                return attempt;
            }
            ComicShot dependency = shotMapper.selectById(shot.getDependsOnShotId());
            if (dependency != null && dependency.getSelectedAttemptId() != null) {
                return attempt;
            }
        }
        return null;
    }

    private String normalizeRunStatus(String status) {
        if (status == null) return "RUNNING";
        if ("SUCCESS".equals(status)) return "SUCCESS";
        if (FAILED_RUN_STATUSES.contains(status)) return status;
        if (ACTIVE_RUN_STATUSES.contains(status)) return status;
        return "RUNNING";
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public enum LaunchResult {
        LAUNCHED,
        NO_CAPACITY,
        NO_READY_ATTEMPT,
        BATCH_INACTIVE,
        RETRY
    }

    public static final class AttemptLaunchException extends RuntimeException {
        private final Long attemptId;
        private final String errorCode;
        private final String errorMessage;

        public AttemptLaunchException(Long attemptId, String errorCode, String errorMessage, Throwable cause) {
            super(errorMessage, cause);
            this.attemptId = attemptId;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public Long attemptId() {
            return attemptId;
        }

        public String errorCode() {
            return errorCode;
        }

        public String errorMessage() {
            return errorMessage;
        }
    }
}
