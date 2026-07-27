package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.ppt.config.PptEngineProperties;
import com.aiminilab.aitoolmarket.ppt.domain.PptJobStatus;
import com.aiminilab.aitoolmarket.ppt.domain.PptJobType;
import com.aiminilab.aitoolmarket.ppt.domain.PptJobChainPolicy;
import com.aiminilab.aitoolmarket.ppt.dto.PptJobView;
import com.aiminilab.aitoolmarket.ppt.dto.SubmitPptJobRequest;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobRequest;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobSnapshot;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobState;
import com.aiminilab.aitoolmarket.ppt.engine.EngineProject;
import com.aiminilab.aitoolmarket.ppt.engine.EngineProjectRequest;
import com.aiminilab.aitoolmarket.ppt.engine.EngineSubmission;
import com.aiminilab.aitoolmarket.ppt.engine.PptEngineAdapter;
import com.aiminilab.aitoolmarket.ppt.engine.PptEngineRegistry;
import com.aiminilab.aitoolmarket.ppt.engine.banana.BananaPptEngineAdapter;
import com.aiminilab.aitoolmarket.ppt.entity.PptEngineBinding;
import com.aiminilab.aitoolmarket.ppt.entity.PptExport;
import com.aiminilab.aitoolmarket.ppt.entity.PptJob;
import com.aiminilab.aitoolmarket.ppt.entity.PptProject;
import com.aiminilab.aitoolmarket.ppt.mapper.PptEngineBindingMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptExportMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptJobMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptProjectMapper;
import com.aiminilab.aitoolmarket.ppt.security.PptExecutionTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PptJobApplicationService {
    private static final Logger LOG = LoggerFactory.getLogger(PptJobApplicationService.class);
    private static final Duration POLL_DELAY = Duration.ofSeconds(3);
    private static final Duration UNKNOWN_OUTCOME_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    private final String leaseOwnerPrefix = "ppt-job-" + UUID.randomUUID();

    private final PptWorkspaceService workspaceService;
    private final PptProjectMapper projectMapper;
    private final PptJobMapper jobMapper;
    private final PptEngineBindingMapper bindingMapper;
    private final PptExportMapper exportMapper;
    private final PptEngineRegistry engineRegistry;
    private final PptPlatformModelBindingService platformModelBindingService;
    private final PptExecutionTokenService executionTokenService;
    private final CreditService creditService;
    private final PptEngineProperties properties;
    private final ObjectMapper objectMapper;

    public PptJobApplicationService(PptWorkspaceService workspaceService,
                                    PptProjectMapper projectMapper,
                                    PptJobMapper jobMapper,
                                    PptEngineBindingMapper bindingMapper,
                                    PptExportMapper exportMapper,
                                    PptEngineRegistry engineRegistry,
                                    PptPlatformModelBindingService platformModelBindingService,
                                    PptExecutionTokenService executionTokenService,
                                    CreditService creditService,
                                    PptEngineProperties properties,
                                    ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.projectMapper = projectMapper;
        this.jobMapper = jobMapper;
        this.bindingMapper = bindingMapper;
        this.exportMapper = exportMapper;
        this.engineRegistry = engineRegistry;
        this.platformModelBindingService = platformModelBindingService;
        this.executionTokenService = executionTokenService;
        this.creditService = creditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public PptJobView submit(Long userId, Long projectId, SubmitPptJobRequest request) {
        PptProject project = workspaceService.requireProject(userId, projectId);
        String idempotencyKey = "ppt:" + userId + ":" + request.clientRequestId().trim();
        PptJob existing = jobMapper.findByIdempotency(userId, idempotencyKey);
        if (existing != null) {
            if (!existing.getProjectId().equals(projectId)
                    || !existing.getJobType().equalsIgnoreCase(request.jobType())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "clientRequestId 已被其他 PPT 操作使用");
            }
            return workspaceService.toJobView(existing);
        }
        PptJobType type = parseJobType(request.jobType());
        LocalDateTime now = LocalDateTime.now();
        PptJob job = new PptJob();
        job.setUserId(userId);
        job.setProjectId(projectId);
        job.setJobType(type.name());
        job.setStatus(PptJobStatus.CREATED.name());
        job.setEngineCode(BananaPptEngineAdapter.ENGINE_CODE);
        job.setIdempotencyKey(idempotencyKey);
        job.setAttemptNo(1);
        job.setProgress(0);
        job.setProgressMessage("等待提交");
        job.setRequestJson(writeJson(request.payload()));
        job.setRetryable(false);
        job.setReservedCredits(0);
        job.setActualCredits(0);
        job.setCreditState("NOT_REQUIRED");
        job.setNextPollAt(now);
        job.setDeadlineAt(now.plus(stageDeadline(type)));
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insertJob(job);
        advanceWithLease(job.getId());
        return workspaceService.toJobView(jobMapper.findByIdAndUser(job.getId(), userId));
    }

    public PptJobView get(Long userId, Long jobId) {
        PptJob job = requireJob(userId, jobId);
        if (!PptJobStatus.valueOf(job.getStatus()).terminal()) {
            advanceWithLease(job.getId());
            job = requireJob(userId, jobId);
        }
        return workspaceService.toJobView(job);
    }

    public PptJobView retry(Long userId, Long jobId, String clientRequestId) {
        PptJob previous = requireJob(userId, jobId);
        if (!"FAILED".equals(previous.getStatus()) || !Boolean.TRUE.equals(previous.getRetryable())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前任务不可重试");
        }
        JsonNode payload = readJson(previous.getRequestJson());
        return submit(
                userId,
                previous.getProjectId(),
                new SubmitPptJobRequest(previous.getJobType(), clientRequestId, payload)
        );
    }

    public PptJobView cancel(Long userId, Long jobId) {
        PptJob job = requireJob(userId, jobId);
        if (PptJobStatus.valueOf(job.getStatus()).terminal()) {
            return workspaceService.toJobView(job);
        }
        String cancelLeaseOwner = leaseOwnerPrefix + ":cancel:" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        if (jobMapper.claimLease(jobId, cancelLeaseOwner, now, now.plus(LEASE_DURATION)) != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "任务正在推进，请稍后重试取消");
        }
        try {
            job = requireJob(userId, jobId);
            if (PptJobStatus.valueOf(job.getStatus()).terminal()) {
                return workspaceService.toJobView(job);
            }
            if (job.getExternalJobId() != null) {
                PptEngineBinding binding = bindingMapper.findByProjectAndEngine(job.getProjectId(), job.getEngineCode());
                boolean cancelled = binding != null
                        && engineRegistry.require(job.getEngineCode())
                        .cancel(binding.getExternalProjectId(), job.getExternalJobId());
                if (!cancelled) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "当前引擎不支持取消已提交任务");
                }
            }
            String creditState = value(job.getReservedCredits()) > 0 ? "RELEASE_PENDING" : "NOT_REQUIRED";
            int changed = jobMapper.finish(
                    job.getId(), PptJobStatus.CANCELLED.name(), value(job.getProgress()), "已取消",
                    job.getResultJson(), null, null, false, 0, creditState
            );
            if (changed == 1) {
                runPendingCreditOperation(jobMapper.selectById(job.getId()));
            }
            return workspaceService.toJobView(requireJob(userId, jobId));
        } finally {
            jobMapper.releaseLease(jobId, cancelLeaseOwner);
        }
    }

    @Scheduled(fixedDelayString = "${ppt.jobs.poll-interval-ms:3000}")
    public void recoverJobs() {
        for (PptJob job : jobMapper.findRecoverable(LocalDateTime.now(), 20)) {
            try {
                advanceWithLease(job.getId());
            } catch (Exception exception) {
                LOG.warn("PPT job recovery failed: jobId={}, status={}", job.getId(), job.getStatus(), exception);
            }
        }
        for (PptJob job : jobMapper.findPendingCreditOperations(20)) {
            try {
                runPendingCreditOperation(job);
            } catch (Exception exception) {
                LOG.warn("PPT job credit recovery failed: jobId={}, creditState={}",
                        job.getId(), job.getCreditState(), exception);
            }
        }
    }

    private void dispatch(Long jobId) {
        PptJob job = jobMapper.selectById(jobId);
        if (job == null || !"CREATED".equals(job.getStatus())) {
            return;
        }
        PptEngineAdapter adapter = engineRegistry.require(job.getEngineCode());
        if (!adapter.capabilities().available()) {
            completeFailure(job, "PPT_ENGINE_UNAVAILABLE", "PPT 引擎暂不可用", true);
            return;
        }
        int credits = creditCost(PptJobType.valueOf(job.getJobType()));
        if (jobMapper.markReserved(
                job.getId(), PptJobStatus.CREATED.name(), PptJobStatus.CREDIT_RESERVED.name(),
                credits, credits > 0 ? "RESERVATION_PENDING" : "NOT_REQUIRED", "正在预留算力"
        ) != 1) {
            return;
        }
        job = jobMapper.selectById(jobId);
        if (!ensureCreditReservation(job)) {
            return;
        }
        try {
            PptProject project = workspaceService.requireProject(job.getUserId(), job.getProjectId());
            PptEngineBinding binding = ensureBinding(project, adapter);
            EngineSubmission submission = submitToEngine(project, job, binding, adapter);
            if (submission.state() == EngineJobState.SUCCEEDED) {
                completeSuccess(job, binding, submission.result());
                return;
            }
            jobMapper.updateActiveState(
                    job.getId(),
                    submission.state() == EngineJobState.RUNNING
                            ? PptJobStatus.RUNNING.name()
                            : PptJobStatus.SUBMITTED.name(),
                    submission.externalJobId(),
                    submission.progress(),
                    defaultValue(submission.message(), "任务已提交"),
                    writeJson(submission.result()),
                    LocalDateTime.now().plus(POLL_DELAY)
            );
            projectMapper.updateStatus(project.getId(), project.getUserId(), "GENERATING");
        } catch (PptEngineTransportException exception) {
            handleTransportFailure(job, exception);
        } catch (PptEngineResponseException exception) {
            completeFailure(
                    job,
                    exception.getFailureCode(),
                    exception.getMessage(),
                    exception.isRetryable()
            );
        } catch (Exception exception) {
            markUnknown(job, exception);
        }
    }

    private void advance(PptJob job) {
        if (job.getDeadlineAt() != null && !LocalDateTime.now().isBefore(job.getDeadlineAt())) {
            completeFailure(job, "PPT_STAGE_TIMEOUT", "PPT 阶段超过最大执行时间", true);
            return;
        }
        PptJobStatus status = PptJobStatus.valueOf(job.getStatus());
        if (status == PptJobStatus.CREATED) {
            dispatch(job.getId());
            return;
        }
        if (status == PptJobStatus.CREDIT_RESERVED) {
            if (!ensureCreditReservation(job)) {
                return;
            }
            job = jobMapper.selectById(job.getId());
            dispatchReserved(job);
            return;
        }
        if (status == PptJobStatus.RECONCILING && job.getExternalJobId() == null) {
            PptEngineBinding binding = bindingMapper.findByProjectAndEngine(job.getProjectId(), job.getEngineCode());
            if (binding == null) {
                try {
                    PptProject project = workspaceService.requireProject(job.getUserId(), job.getProjectId());
                    binding = ensureBinding(project, engineRegistry.require(job.getEngineCode()));
                } catch (PptEngineResponseException | PptEngineTransportException exception) {
                    LOG.debug("PPT project binding recovery is temporarily unavailable: jobId={}",
                            job.getId(), exception);
                }
            }
            if (binding != null) {
                try {
                    EngineSubmission receipt = engineRegistry.require(job.getEngineCode())
                            .reconcileSubmission(binding.getExternalProjectId(), job.getIdempotencyKey());
                    if (receipt.state() == EngineJobState.SUCCEEDED) {
                        completeSuccess(job, binding, receipt.result());
                        return;
                    }
                    if (receipt.state() == EngineJobState.FAILED) {
                        String receiptError = firstText(receipt.result(), "error_code", "errorCode");
                        if ("ENGINE_INTERRUPTED".equals(receiptError)
                                && receipt.result() != null
                                && receipt.result().path("retryable").asBoolean(false)) {
                            PptProject project = workspaceService.requireProject(job.getUserId(), job.getProjectId());
                            EngineSubmission restarted = submitToEngine(
                                    project, job, binding, engineRegistry.require(job.getEngineCode()));
                            if (restarted.state() == EngineJobState.SUCCEEDED) {
                                completeSuccess(job, binding, restarted.result());
                            } else {
                                jobMapper.updateActiveState(
                                        job.getId(),
                                        restarted.state() == EngineJobState.RUNNING
                                                ? PptJobStatus.RUNNING.name()
                                                : PptJobStatus.SUBMITTED.name(),
                                        restarted.externalJobId(), restarted.progress(),
                                        defaultValue(restarted.message(), "已重新驱动中断任务"),
                                        writeJson(restarted.result()), LocalDateTime.now().plus(POLL_DELAY));
                            }
                            return;
                        }
                        completeFailure(
                                job,
                                defaultValue(receiptError, "PPT_ENGINE_JOB_FAILED"),
                                defaultValue(firstText(receipt.result(), "error_message", "message"),
                                        "PPT 引擎任务失败"),
                                receipt.result() != null && receipt.result().path("retryable").asBoolean(false));
                        return;
                    }
                    if (receipt.state() == EngineJobState.CANCELLED) {
                        completeFailure(job, "PPT_ENGINE_CANCELLED", "PPT 引擎任务已取消", true);
                        return;
                    }
                    if (receipt.externalJobId() != null
                            && (receipt.state() == EngineJobState.SUBMITTED
                            || receipt.state() == EngineJobState.RUNNING)) {
                        jobMapper.updateActiveState(
                                job.getId(),
                                receipt.state() == EngineJobState.RUNNING
                                        ? PptJobStatus.RUNNING.name()
                                        : PptJobStatus.SUBMITTED.name(),
                                receipt.externalJobId(), receipt.progress(),
                                defaultValue(receipt.message(), "已恢复引擎任务"),
                                writeJson(receipt.result()), LocalDateTime.now().plus(POLL_DELAY));
                        return;
                    }
                } catch (PptEngineResponseException | PptEngineTransportException exception) {
                    LOG.debug("PPT submission receipt is temporarily unavailable: jobId={}", job.getId(), exception);
                }
            }
            LocalDateTime reconcileStarted = job.getReconcileStartedAt() == null
                    ? job.getUpdatedAt()
                    : job.getReconcileStartedAt();
            if (Duration.between(reconcileStarted, LocalDateTime.now()).compareTo(UNKNOWN_OUTCOME_TIMEOUT) >= 0) {
                completeFailure(job, "PPT_ENGINE_OUTCOME_UNKNOWN", "引擎提交结果无法确认", true);
            } else {
                jobMapper.updateActiveState(
                        job.getId(), PptJobStatus.RECONCILING.name(), null,
                        value(job.getProgress()), "等待人工或超时对账", job.getResultJson(),
                        LocalDateTime.now().plus(reconciliationDelay(reconcileStarted))
                );
            }
            return;
        }
        if (job.getExternalJobId() == null) {
            return;
        }
        PptEngineBinding binding = bindingMapper.findByProjectAndEngine(job.getProjectId(), job.getEngineCode());
        if (binding == null) {
            completeFailure(job, "PPT_ENGINE_BINDING_MISSING", "PPT 引擎项目绑定缺失", false);
            return;
        }
        try {
            EngineJobSnapshot snapshot = engineRegistry.require(job.getEngineCode())
                    .query(binding.getExternalProjectId(), job.getExternalJobId());
            jobMapper.touchEngineHeartbeat(job.getId());
            switch (snapshot.state()) {
                case SUCCEEDED -> completeSuccess(job, binding, snapshot.result());
                case FAILED -> completeFailure(
                        job,
                        defaultValue(snapshot.errorCode(), "PPT_ENGINE_JOB_FAILED"),
                        defaultValue(snapshot.errorMessage(), "PPT 引擎任务失败"),
                        snapshot.retryable()
                );
                case CANCELLED -> {
                    String creditState = value(job.getReservedCredits()) > 0
                            ? "RELEASE_PENDING"
                            : "NOT_REQUIRED";
                    int changed = jobMapper.finish(
                            job.getId(), PptJobStatus.CANCELLED.name(), snapshot.progress(),
                            defaultValue(snapshot.message(), "已取消"), writeJson(snapshot.result()),
                            null, null, false, 0, creditState
                    );
                    if (changed == 1) {
                        runPendingCreditOperation(jobMapper.selectById(job.getId()));
                    }
                }
                case SUBMITTED, RUNNING, UNKNOWN -> jobMapper.updateActiveState(
                        job.getId(),
                        snapshot.state() == EngineJobState.SUBMITTED
                                ? PptJobStatus.SUBMITTED.name()
                                : PptJobStatus.RUNNING.name(),
                        job.getExternalJobId(),
                        snapshot.progress(),
                        defaultValue(snapshot.message(), "正在生成"),
                        writeJson(snapshot.result()),
                        LocalDateTime.now().plus(POLL_DELAY)
                );
            }
        } catch (Exception exception) {
            jobMapper.updateActiveState(
                    job.getId(), PptJobStatus.RECONCILING.name(), job.getExternalJobId(),
                    value(job.getProgress()), "引擎暂时不可达，等待对账", job.getResultJson(),
                    LocalDateTime.now().plusSeconds(30)
            );
        }
    }

    private void dispatchReserved(PptJob job) {
        try {
            PptProject project = workspaceService.requireProject(job.getUserId(), job.getProjectId());
            PptEngineAdapter adapter = engineRegistry.require(job.getEngineCode());
            PptEngineBinding binding = ensureBinding(project, adapter);
            EngineSubmission submission = submitToEngine(project, job, binding, adapter);
            if (submission.state() == EngineJobState.SUCCEEDED) {
                completeSuccess(job, binding, submission.result());
                return;
            }
            jobMapper.updateActiveState(
                    job.getId(), PptJobStatus.SUBMITTED.name(), submission.externalJobId(),
                    submission.progress(), defaultValue(submission.message(), "任务已提交"),
                    writeJson(submission.result()), LocalDateTime.now().plus(POLL_DELAY)
            );
        } catch (PptEngineTransportException exception) {
            handleTransportFailure(job, exception);
        } catch (PptEngineResponseException exception) {
            completeFailure(
                    job,
                    exception.getFailureCode(),
                    exception.getMessage(),
                    exception.isRetryable()
            );
        } catch (Exception exception) {
            markUnknown(job, exception);
        }
    }

    private PptEngineBinding ensureBinding(PptProject project, PptEngineAdapter adapter) {
        PptEngineBinding existing = bindingMapper.findByProjectAndEngine(project.getId(), adapter.engineCode());
        if (existing != null) {
            return existing;
        }
        EngineProject engineProject = adapter.createProject(new EngineProjectRequest(
                project.getTitle(),
                project.getTopic(),
                project.getCreationType(),
                project.getLanguage(),
                project.getAspectRatio(),
                project.getPageCount(),
                project.getId(),
                "ppt-project:" + project.getId() + ":create"
        ));
        LocalDateTime now = LocalDateTime.now();
        PptEngineBinding binding = new PptEngineBinding();
        binding.setProjectId(project.getId());
        binding.setEngineCode(adapter.engineCode());
        binding.setExternalProjectId(engineProject.externalProjectId());
        binding.setCreatedAt(now);
        binding.setUpdatedAt(now);
        bindingMapper.insertBinding(binding);
        return binding;
    }

    private EngineSubmission submitToEngine(PptProject project,
                                            PptJob job,
                                            PptEngineBinding binding,
                                            PptEngineAdapter adapter) {
        // Resolve before issuing the engine token so a task never starts with
        // missing or disabled platform model bindings.
        platformModelBindingService.resolve(project);
        var token = executionTokenService.issue(
                project.getId(),
                job.getId(),
                List.of("TEXT_GENERATION", "IMAGE_GENERATION"),
                properties.getExecutionTokenTtlSeconds()
        );
        return adapter.submit(new EngineJobRequest(
                PptJobType.valueOf(job.getJobType()),
                binding.getExternalProjectId(),
                readJson(job.getRequestJson()),
                project.getId(),
                job.getId(),
                job.getIdempotencyKey(),
                properties.getModelGatewayBaseUrl(),
                token.token()
        ));
    }

    private void completeSuccess(PptJob job, PptEngineBinding binding, JsonNode engineResult) {
        ObjectNode platformResult;
        if (job.getJobType().startsWith("EXPORT_")) {
            platformResult = objectMapper.createObjectNode();
            platformResult.put("exportType", exportType(job));
        } else {
            JsonNode snapshot = engineRegistry.require(job.getEngineCode())
                    .projectSnapshot(binding.getExternalProjectId());
            platformResult = normalizeDeck(job.getProjectId(), snapshot, engineResult);
        }
        int credits = value(job.getReservedCredits());
        String creditState = credits > 0 ? "SETTLEMENT_PENDING" : "NOT_REQUIRED";
        int changed = jobMapper.finish(
                job.getId(), PptJobStatus.SUCCEEDED.name(), 100, "已完成",
                writeJson(platformResult), null, null, false, credits,
                creditState
        );
        if (changed != 1) {
            return;
        }
        runPendingCreditOperation(jobMapper.selectById(job.getId()));
        if (job.getJobType().startsWith("EXPORT_")) {
            persistExport(job, engineResult);
        }
        projectMapper.updateStatus(job.getProjectId(), job.getUserId(), "READY");
        try {
            scheduleNextStage(job);
        } catch (RuntimeException exception) {
            LOG.error("PPT automatic continuation failed after a completed stage: jobId={}", job.getId(), exception);
        }
    }

    private void scheduleNextStage(PptJob completed) {
        JsonNode request = readJson(completed.getRequestJson());
        PptJobType current = PptJobType.valueOf(completed.getJobType());
        PptJobType next = PptJobChainPolicy.next(current, request);
        if (next == null) {
            return;
        }
        ObjectNode payload = request.isObject()
                ? ((ObjectNode) request).deepCopy()
                : objectMapper.createObjectNode();
        payload.put("autoContinue", true);
        submit(
                completed.getUserId(),
                completed.getProjectId(),
                new SubmitPptJobRequest(
                        next.name(),
                        "auto-" + completed.getProjectId() + "-" + next.name().toLowerCase(Locale.ROOT),
                        payload
                )
        );
    }

    private void completeFailure(PptJob job, String code, String message, boolean retryable) {
        String creditState = value(job.getReservedCredits()) > 0 ? "RELEASE_PENDING" : "NOT_REQUIRED";
        int changed = jobMapper.finish(
                job.getId(), PptJobStatus.FAILED.name(), value(job.getProgress()), "生成失败",
                job.getResultJson(), code, sanitize(message), retryable, 0, creditState
        );
        if (changed == 1) {
            runPendingCreditOperation(jobMapper.selectById(job.getId()));
        }
        projectMapper.updateStatus(job.getProjectId(), job.getUserId(), "FAILED");
    }

    private void markUnknown(PptJob job, Exception exception) {
        LOG.warn("PPT engine submission outcome is unknown: jobId={}", job.getId(), exception);
        jobMapper.markReconciliationStarted(job.getId());
        jobMapper.updateActiveState(
                job.getId(), PptJobStatus.RECONCILING.name(), job.getExternalJobId(),
                value(job.getProgress()), "提交结果待确认", job.getResultJson(),
                LocalDateTime.now().plusSeconds(2)
        );
    }

    private void handleTransportFailure(PptJob job, PptEngineTransportException exception) {
        if (exception.isOutcomeUnknown()) {
            markUnknown(job, exception);
            return;
        }
        completeFailure(job, "PPT_ENGINE_UNAVAILABLE", exception.getMessage(), true);
    }

    private void advanceWithLease(Long jobId) {
        LocalDateTime now = LocalDateTime.now();
        String operationLeaseOwner = leaseOwnerPrefix + ":advance:" + UUID.randomUUID();
        if (jobMapper.claimLease(jobId, operationLeaseOwner, now, now.plus(LEASE_DURATION)) != 1) {
            return;
        }
        try {
            PptJob claimed = jobMapper.selectById(jobId);
            if (claimed != null && !PptJobStatus.valueOf(claimed.getStatus()).terminal()) {
                advance(claimed);
            }
        } finally {
            jobMapper.releaseLease(jobId, operationLeaseOwner);
        }
    }

    private boolean ensureCreditReservation(PptJob job) {
        int credits = value(job.getReservedCredits());
        if (credits <= 0 || "NOT_REQUIRED".equals(job.getCreditState()) || "RESERVED".equals(job.getCreditState())) {
            return true;
        }
        if (!"RESERVATION_PENDING".equals(job.getCreditState())) {
            completeFailure(job, "PPT_CREDIT_STATE_INVALID", "PPT 算力预留状态异常", true);
            return false;
        }
        boolean reserved = creditService.tryFreeze(
                job.getUserId(), CreditSourceType.PPT_STEP, job.getId(), credits, reserveKey(job));
        if (!reserved) {
            jobMapper.finish(
                    job.getId(), PptJobStatus.FAILED.name(), value(job.getProgress()), "算力不足",
                    job.getResultJson(), ErrorCode.CREDIT_NOT_ENOUGH.name(), "可用算力不足",
                    true, 0, "NOT_RESERVED"
            );
            return false;
        }
        if (jobMapper.updateCreditState(job.getId(), "RESERVATION_PENDING", "RESERVED", 0) == 1) {
            return true;
        }
        PptJob current = jobMapper.selectById(job.getId());
        if (current != null && "RESERVED".equals(current.getCreditState())) {
            return true;
        }
        creditService.releaseReserved(
                job.getUserId(), CreditSourceType.PPT_STEP, job.getId(), credits, releaseKey(job));
        return false;
    }

    private Duration stageDeadline(PptJobType type) {
        return switch (type) {
            case GENERATE_OUTLINE -> Duration.ofMinutes(10);
            case GENERATE_DESCRIPTIONS -> Duration.ofMinutes(20);
            case GENERATE_IMAGES -> Duration.ofMinutes(60);
            case EXPORT_EDITABLE_PPTX -> Duration.ofMinutes(90);
            default -> Duration.ofMinutes(15);
        };
    }

    private Duration reconciliationDelay(LocalDateTime startedAt) {
        long elapsedSeconds = Math.max(0, Duration.between(startedAt, LocalDateTime.now()).toSeconds());
        if (elapsedSeconds < 2) {
            return Duration.ofSeconds(2);
        }
        if (elapsedSeconds < 7) {
            return Duration.ofSeconds(5);
        }
        if (elapsedSeconds < 17) {
            return Duration.ofSeconds(10);
        }
        return Duration.ofSeconds(30);
    }

    private void persistExport(PptJob job, JsonNode engineResult) {
        String enginePath = firstText(engineResult, "download_url", "file_url", "path");
        if (enginePath == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        PptExport export = new PptExport();
        export.setUserId(job.getUserId());
        export.setProjectId(job.getProjectId());
        export.setJobId(job.getId());
        export.setExportType(exportType(job));
        export.setStatus("READY");
        export.setFileName(defaultValue(firstText(engineResult, "filename"), defaultExportName(job)));
        export.setContentType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        export.setStorageUrl(enginePath);
        export.setCreatedAt(now);
        export.setUpdatedAt(now);
        exportMapper.insertExport(export);
    }

    private ObjectNode normalizeDeck(Long projectId, JsonNode snapshot, JsonNode actionResult) {
        ObjectNode deck = objectMapper.createObjectNode();
        deck.put("projectId", projectId);
        String title = firstText(snapshot, "title", "name");
        if (title != null) {
            deck.put("title", title);
        }
        JsonNode outline = snapshot == null ? null : snapshot.get("outline");
        if (outline == null && actionResult != null) {
            outline = actionResult.get("outline");
        }
        if (outline != null) {
            deck.set("outline", outline.deepCopy());
        }
        ArrayNode slides = deck.putArray("slides");
        JsonNode pages = snapshot == null ? null : snapshot.get("pages");
        if (pages != null && pages.isArray()) {
            int index = 0;
            for (JsonNode page : pages) {
                index++;
                ObjectNode slide = slides.addObject();
                slide.put("slideId", defaultValue(firstText(page, "id", "page_id"), String.valueOf(index)));
                slide.put("slideNo", number(page, "order_index", number(page, "page_number", index)));
                putIfPresent(slide, "title", defaultValue(
                        firstText(page, "title", "outline"),
                        nestedText(page, "outline_content", "title")
                ));
                putIfPresent(slide, "description", defaultValue(
                        firstText(page, "description", "content"),
                        nestedText(page, "description_content", "text", "description")
                ));
                String preview = firstText(
                        page, "generated_image_url", "image_url", "preview_url", "image");
                if (preview != null) {
                    slide.put("previewUrl", proxyEngineUrl(projectId, preview));
                }
            }
        }
        return deck;
    }

    private String nestedText(JsonNode node, String objectField, String... fieldNames) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode nested = node.get(objectField);
        return firstText(nested, fieldNames);
    }

    public byte[] downloadExport(Long userId, Long projectId, Long exportId) {
        workspaceService.requireProject(userId, projectId);
        PptExport export = exportMapper.findOwned(exportId, projectId, userId);
        if (export == null || !"READY".equals(export.getStatus()) || export.getStorageUrl() == null) {
            throw new BusinessException(ErrorCode.PPT_EXPORT_FAILED, "导出文件不存在");
        }
        PptEngineBinding binding = bindingMapper.findByProjectAndEngine(
                projectId, BananaPptEngineAdapter.ENGINE_CODE);
        if (binding == null) {
            throw new BusinessException(ErrorCode.PPT_EXPORT_FAILED, "PPT 引擎项目绑定缺失");
        }
        return engineRegistry.require(binding.getEngineCode()).download(export.getStorageUrl());
    }

    public byte[] downloadEngineFile(Long userId, Long projectId, String relativePath) {
        workspaceService.requireProject(userId, projectId);
        PptEngineBinding binding = bindingMapper.findByProjectAndEngine(
                projectId, BananaPptEngineAdapter.ENGINE_CODE);
        if (binding == null) {
            throw new BusinessException(ErrorCode.PPT_PROJECT_NOT_FOUND, "PPT 引擎项目绑定缺失");
        }
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法文件路径");
        }
        if (normalized.startsWith("files/")) {
            normalized = normalized.substring("files/".length());
        }
        if (!normalized.startsWith(binding.getExternalProjectId() + "/")) {
            normalized = binding.getExternalProjectId() + "/" + normalized;
        }
        return engineRegistry.require(binding.getEngineCode()).download("/files/" + normalized);
    }

    private PptJob requireJob(Long userId, Long jobId) {
        PptJob job = jobMapper.findByIdAndUser(jobId, userId);
        if (job == null) {
            throw new BusinessException(ErrorCode.PPT_TASK_FAILED, "PPT 任务不存在");
        }
        return job;
    }

    private PptJobType parseJobType(String raw) {
        try {
            return PptJobType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的 PPT 任务类型");
        }
    }

    private int creditCost(PptJobType type) {
        return switch (type) {
            case GENERATE_OUTLINE -> properties.getBilling().getGenerateOutlineCredits();
            case GENERATE_DESCRIPTIONS -> properties.getBilling().getGenerateDescriptionsCredits();
            case GENERATE_IMAGES -> properties.getBilling().getGenerateImagesCredits();
            case EXPORT_PPTX -> properties.getBilling().getExportPptxCredits();
            case EXPORT_EDITABLE_PPTX -> properties.getBilling().getExportEditablePptxCredits();
        };
    }

    private void runPendingCreditOperation(PptJob job) {
        if (job == null) {
            return;
        }
        int credits = value(job.getReservedCredits());
        if (credits <= 0) {
            return;
        }
        if ("SETTLEMENT_PENDING".equals(job.getCreditState())) {
            creditService.captureReserved(
                    job.getUserId(), CreditSourceType.PPT_STEP, job.getId(),
                    credits, credits, settleKey(job)
            );
            jobMapper.updateCreditState(
                    job.getId(), "SETTLEMENT_PENDING", "SETTLED", credits);
            return;
        }
        if ("RELEASE_PENDING".equals(job.getCreditState())) {
            creditService.releaseReserved(
                    job.getUserId(), CreditSourceType.PPT_STEP, job.getId(), credits, releaseKey(job));
            jobMapper.updateCreditState(
                    job.getId(), "RELEASE_PENDING", "RELEASED", 0);
        }
    }

    private String reserveKey(PptJob job) { return "ppt-job:" + job.getId() + ":reserve"; }
    private String settleKey(PptJob job) { return "ppt-job:" + job.getId() + ":settle"; }
    private String releaseKey(PptJob job) { return "ppt-job:" + job.getId() + ":release"; }

    private String writeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("PPT JSON 序列化失败", exception);
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("PPT 任务 JSON 损坏", exception);
        }
    }

    private String proxyEngineUrl(Long projectId, String engineUrl) {
        String path = engineUrl;
        int files = path.indexOf("/files/");
        if (files >= 0) {
            path = path.substring(files + "/files/".length());
        } else {
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
        }
        return "/api/v2/ppt/projects/" + projectId + "/files/" + path;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && value.isValueNode()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private int number(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private String exportType(PptJob job) {
        return "EXPORT_EDITABLE_PPTX".equals(job.getJobType()) ? "EDITABLE_PPTX" : "PPTX";
    }

    private String defaultExportName(PptJob job) {
        return "presentation-" + job.getProjectId() + ".pptx";
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "PPT 引擎任务失败";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }
}
