package com.aiminilab.aitoolmarket.task.routing;

import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.error.ErrorMessageSanitizer;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.task.dto.RouteFailoverRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.aiminilab.aitoolmarket.task.routing.entity.TaskModelRouteAttempt;
import com.aiminilab.aitoolmarket.task.routing.mapper.AccountModelRouteStateMapper;
import com.aiminilab.aitoolmarket.task.routing.mapper.TaskModelRouteAttemptMapper;
import com.aiminilab.aitoolmarket.task.support.TaskFailureMessage;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ModelRoutingService {
    private static final String ACTIVE = "ACTIVE";
    private static final String SWITCHED = "SWITCHED";

    private final TaskMapper taskMapper;
    private final ToolMapper toolMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final ModelVendorAccountMapper accountMapper;
    private final TaskModelRouteAttemptMapper attemptMapper;
    private final AccountModelRouteStateMapper stateMapper;
    private final ModelCapabilityService capabilityService;
    private final ModelExecutionSnapshotService snapshotService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public ModelRoutingService(TaskMapper taskMapper,
                               ToolMapper toolMapper,
                               AgentModelConfigMapper modelConfigMapper,
                               ModelVendorAccountMapper accountMapper,
                               TaskModelRouteAttemptMapper attemptMapper,
                               AccountModelRouteStateMapper stateMapper,
                               ModelCapabilityService capabilityService,
                               ModelExecutionSnapshotService snapshotService,
                               ObjectMapper objectMapper,
                               AppProperties appProperties) {
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.accountMapper = accountMapper;
        this.attemptMapper = attemptMapper;
        this.stateMapper = stateMapper;
        this.capabilityService = capabilityService;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    @Transactional
    public void assignInitialRoute(AiTask task, AgentModelConfig originalModelConfig) {
        if (task == null || task.getId() == null || originalModelConfig == null) {
            return;
        }
        if (!routingEnabled() || originalModelConfig.getRoutingPoolId() == null) {
            return;
        }
        if (originalModelConfig.getVendorAccountId() == null) {
            throw routingUnavailable("routing pool model has no anchor account");
        }
        ModelVendorAccount sourceAccount = accountMapper.findActiveById(originalModelConfig.getVendorAccountId());
        if (!accountBelongsToPool(sourceAccount, originalModelConfig.getRoutingPoolId())) {
            throw routingUnavailable("routing pool does not match the anchor account");
        }
        CandidatePool pool = lockCandidatePool(
                originalModelConfig, sourceAccount, requiredCapabilitiesForTask(task));
        ModelRoutingPolicy.Candidate selected = ModelRoutingPolicy.choose(
                pool.candidates().stream().filter(this::candidateCanReceiveNewTasks).toList(),
                LocalDateTime.now()
        );
        if (selected == null) {
            throw routingUnavailable("no eligible model account in the selected routing pool");
        }

        reserve(selected.state());
        TaskModelRouteAttempt attempt = newAttempt(
                task.getId(), attemptMapper.countByTaskId(task.getId()) + 1,
                selected.modelConfig(), selected.account(), null
        );
        attemptMapper.insertAttempt(attempt);
        ModelExecutionSnapshot snapshot = snapshotService.create(selected.modelConfig());
        String snapshotJson = snapshotService.serialize(snapshot);
        int updated = taskMapper.assignInitialRoute(
                task.getId(), selected.modelConfig().getId(), selected.account().getId(),
                attempt.getId(), snapshotJson
        );
        if (updated == 0) {
            throw new IllegalStateException("task route was already assigned");
        }
        task.setSelectedModelConfigId(selected.modelConfig().getId());
        task.setSelectedVendorAccountId(selected.account().getId());
        task.setCurrentRouteAttemptId(attempt.getId());
        task.setModelSnapshotJson(snapshotJson);
    }

    @Transactional
    public void assignRetryRoute(AiTask task, AgentModelConfig originalModelConfig) {
        if (task == null || task.getId() == null || originalModelConfig == null) {
            return;
        }
        String originalSnapshot = snapshotService.serialize(snapshotService.create(originalModelConfig));
        if (taskMapper.clearRouteForRetry(
                task.getId(), originalModelConfig.getId(), originalModelConfig.getVendorAccountId(), originalSnapshot) == 0) {
            throw new IllegalStateException("task is not ready for route retry");
        }
        task.setSelectedModelConfigId(originalModelConfig.getId());
        task.setSelectedVendorAccountId(originalModelConfig.getVendorAccountId());
        task.setCurrentRouteAttemptId(null);
        task.setModelSnapshotJson(originalSnapshot);
        assignInitialRoute(task, originalModelConfig);
    }

    @Transactional
    public FailoverDecision failover(Long taskId, RouteFailoverRequest request) {
        if (!routingEnabled()) {
            return FailoverDecision.notSwitched("routing_disabled", null);
        }
        AiTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            return FailoverDecision.notSwitched("task_not_found", null);
        }
        if (!TaskStatus.PROCESSING.name().equals(task.getStatus())) {
            return FailoverDecision.notSwitched("task_not_processing", task.getCurrentRouteAttemptId());
        }
        if (request == null || isBlank(request.claimToken())
                || !Objects.equals(task.getClaimToken(), request.claimToken().trim())) {
            return FailoverDecision.notSwitched("claim_token_mismatch", task.getCurrentRouteAttemptId());
        }
        Long currentAttemptId = task.getCurrentRouteAttemptId();
        if (currentAttemptId == null) {
            return FailoverDecision.notSwitched("task_not_load_balanced", null);
        }
        if (request.routeAttemptId() != null && !request.routeAttemptId().equals(currentAttemptId)) {
            TaskModelRouteAttempt prior = attemptMapper.findByIdForUpdate(request.routeAttemptId());
            if (prior != null && Objects.equals(prior.getTaskId(), taskId)
                    && SWITCHED.equals(prior.getStatus())) {
                return FailoverDecision.switched("already_switched", currentAttemptId);
            }
            return FailoverDecision.notSwitched("stale_route_attempt", currentAttemptId);
        }

        TaskModelRouteAttempt currentAttempt = attemptMapper.findByIdForUpdate(currentAttemptId);
        if (currentAttempt == null || !Objects.equals(currentAttempt.getTaskId(), taskId)
                || !ACTIVE.equals(currentAttempt.getStatus())) {
            return FailoverDecision.notSwitched("route_attempt_not_active", currentAttemptId);
        }
        boolean hasCheckpoint = !isBlank(task.getProviderCheckpointJson());
        if (!ModelRoutingPolicy.canFailover(
                request,
                hasCheckpoint,
                currentAttempt.getProviderRequestId(),
                currentAttempt.getProviderCharged())) {
            return FailoverDecision.notSwitched("unsafe_delivery_state", currentAttemptId);
        }

        int attemptCount = attemptMapper.countByTaskId(taskId);
        int maxAttempts = 1 + appProperties.getModelRouting().getMaxFailovers();
        if (attemptCount >= maxAttempts) {
            return FailoverDecision.notSwitched("failover_limit_reached", currentAttemptId);
        }
        AgentModelConfig reference = resolveReferenceModel(task);
        if (reference == null || reference.getVendorAccountId() == null) {
            return FailoverDecision.notSwitched("reference_model_unavailable", currentAttemptId);
        }
        if (reference.getRoutingPoolId() == null) {
            return FailoverDecision.notSwitched("task_not_load_balanced", currentAttemptId);
        }
        ModelVendorAccount sourceAccount = accountMapper.findActiveById(reference.getVendorAccountId());
        if (!accountBelongsToPool(sourceAccount, reference.getRoutingPoolId())) {
            return FailoverDecision.notSwitched("routing_pool_unavailable", currentAttemptId);
        }

        CandidatePool pool = lockCandidatePool(
                reference, sourceAccount, requiredCapabilitiesForTask(task), currentAttempt.getModelConfigId());
        Set<Long> attemptedAccounts = new HashSet<>(attemptMapper.findAttemptedVendorAccountIds(taskId));
        ModelRoutingPolicy.Candidate selected = ModelRoutingPolicy.choose(
                pool.candidates().stream()
                        .filter(this::candidateCanReceiveNewTasks)
                        .filter(candidate -> !attemptedAccounts.contains(candidate.account().getId()))
                        .toList(),
                LocalDateTime.now()
        );
        if (selected == null) {
            return FailoverDecision.notSwitched("no_eligible_pool_account", currentAttemptId);
        }

        AccountModelRouteState currentState = pool.statesByModel().get(currentAttempt.getModelConfigId());
        RouteFailoverRequest safeRequest = sanitize(request);
        FailureContract failureContract = failureContract(task, null, safeRequest);
        if (attemptMapper.closeWithFailureContract(
                currentAttemptId,
                SWITCHED,
                safeRequest,
                failureContract.userMessage(),
                failureContract.developerMessage(),
                failureContract.failureTraceId()
        ) == 0) {
            return FailoverDecision.notSwitched("route_attempt_not_active", currentAttemptId);
        }
        releaseFailure(currentState, circuitDecision(currentState, request));

        reserve(selected.state());
        TaskModelRouteAttempt nextAttempt = newAttempt(
                taskId, attemptCount + 1, selected.modelConfig(), selected.account(), request.claimToken().trim()
        );
        attemptMapper.insertAttempt(nextAttempt);
        String snapshotJson = snapshotService.serialize(snapshotService.create(selected.modelConfig()));
        int updated = taskMapper.switchRouteGuarded(
                taskId,
                request.claimToken().trim(),
                currentAttemptId,
                selected.modelConfig().getId(),
                selected.account().getId(),
                nextAttempt.getId(),
                snapshotJson
        );
        if (updated == 0) {
            throw new IllegalStateException("task route changed during failover");
        }
        return FailoverDecision.switched("switched", nextAttempt.getId());
    }

    @Transactional
    public void completeTask(Long taskId, String outcome) {
        completeTask(taskId, outcome, null);
    }

    @Transactional
    public void completeTask(Long taskId, String outcome, WorkerFailedRequest failure) {
        completeTask(taskId, outcome, failure, null, null);
    }

    @Transactional
    public void completeSuccess(Long taskId, String providerRequestId, Boolean providerCalled) {
        completeTask(
                taskId,
                TaskStatus.SUCCESS.name(),
                null,
                limit(providerRequestId, 128),
                providerCalled
        );
    }

    @Transactional
    public void recordProviderAccepted(Long taskId, String claimToken, String providerRequestId) {
        AiTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null || task.getCurrentRouteAttemptId() == null) {
            return;
        }
        if (!isBlank(task.getClaimToken())
                && (isBlank(claimToken) || !task.getClaimToken().equals(claimToken.trim()))) {
            return;
        }
        TaskModelRouteAttempt attempt = attemptMapper.findByIdForUpdate(task.getCurrentRouteAttemptId());
        if (attempt == null || !Objects.equals(attempt.getTaskId(), taskId) || !ACTIVE.equals(attempt.getStatus())) {
            return;
        }
        String normalizedRequestId = limit(providerRequestId, 128);
        if (attemptMapper.markProviderAccepted(
                attempt.getId(), limit(claimToken, 128), normalizedRequestId) == 0) {
            throw new IllegalStateException("provider checkpoint conflicts with the active route attempt");
        }
    }

    private void completeTask(Long taskId,
                              String outcome,
                              WorkerFailedRequest failure,
                              String providerRequestId,
                              Boolean providerCalled) {
        AiTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null || task.getCurrentRouteAttemptId() == null) {
            return;
        }
        TaskModelRouteAttempt attempt = attemptMapper.findByIdForUpdate(task.getCurrentRouteAttemptId());
        if (attempt == null || !ACTIVE.equals(attempt.getStatus())) {
            return;
        }
        int closed;
        String normalizedOutcome = normalizedOutcome(outcome);
        RouteFailoverRequest failureDetails = failure == null
                ? toRouteFailure(task)
                : toRouteFailure(failure);
        if (TaskStatus.SUCCESS.name().equalsIgnoreCase(normalizedOutcome)
                && (providerRequestId != null || providerCalled != null)) {
            closed = attemptMapper.closeSuccess(
                    attempt.getId(), normalizedOutcome, providerRequestId, providerCalled);
        } else if (TaskStatus.SUCCESS.name().equalsIgnoreCase(normalizedOutcome)) {
            closed = attemptMapper.close(attempt.getId(), normalizedOutcome);
        } else {
            FailureContract failureContract = failureContract(task, failure, failureDetails);
            closed = attemptMapper.closeWithFailureContract(
                    attempt.getId(),
                    normalizedOutcome,
                    failureDetails,
                    failureContract.userMessage(),
                    failureContract.developerMessage(),
                    failureContract.failureTraceId()
            );
        }
        if (closed == 0) {
            return;
        }
        AccountModelRouteState state = findStateForUpdate(attempt.getModelConfigId());
        if (state == null) {
            return;
        }
        if (TaskStatus.SUCCESS.name().equalsIgnoreCase(outcome)) {
            stateMapper.releaseSuccess(state.getId());
        } else if (circuitAffectingFailure(failureDetails)) {
            releaseFailure(state, circuitDecision(state, failureDetails));
        } else {
            stateMapper.releaseNeutral(state.getId());
        }
    }

    @Transactional
    public int reconcile() {
        int closed = attemptMapper.closeAttemptsForTerminalTasks();
        int corrected = stateMapper.reconcileInFlightCounts();
        return closed + corrected;
    }

    public boolean routingEnabled() {
        return appProperties.getModelRouting() != null && appProperties.getModelRouting().isEnabled();
    }

    private CandidatePool lockCandidatePool(AgentModelConfig reference,
                                            ModelVendorAccount sourceAccount,
                                            List<String> requiredCapabilities,
                                            Long... additionalStateModelIds) {
        Long routingPoolId = reference.getRoutingPoolId();
        List<ModelVendorAccount> accounts = accountMapper.findActiveByVendorCode(sourceAccount.getVendorCode())
                .stream()
                .filter(this::accountCanBalance)
                .filter(account -> accountBelongsToPool(account, routingPoolId))
                .toList();
        Map<Long, ModelVendorAccount> accountsById = new HashMap<>();
        for (ModelVendorAccount account : accounts) {
            accountsById.put(account.getId(), account);
        }
        List<AgentModelConfig> rawCandidates = new ArrayList<>(modelConfigMapper.findRoutingCandidates(
                sourceAccount.getVendorCode(), reference.getProvider(), reference.getModelName()
        ));
        if (rawCandidates.stream().noneMatch(candidate -> Objects.equals(candidate.getId(), reference.getId()))) {
            rawCandidates.add(reference);
        }
        List<AgentModelConfig> compatible = ModelRoutingPolicy.deduplicateByAccount(rawCandidates.stream()
                .filter(candidate -> candidate.getVendorAccountId() != null)
                .filter(candidate -> accountsById.containsKey(candidate.getVendorAccountId()))
                .filter(candidate -> ModelRoutingPolicy.compatible(
                        reference, candidate, capabilityService, objectMapper, requiredCapabilities))
                .toList());
        for (AgentModelConfig candidate : compatible) {
            stateMapper.insertIfAbsent(candidate.getVendorAccountId(), candidate.getId());
        }
        Set<Long> stateModelIds = new HashSet<>();
        compatible.stream().map(AgentModelConfig::getId).forEach(stateModelIds::add);
        if (additionalStateModelIds != null) {
            for (Long modelId : additionalStateModelIds) {
                if (modelId != null) {
                    stateModelIds.add(modelId);
                }
            }
        }
        if (stateModelIds.isEmpty()) {
            return new CandidatePool(List.of(), Map.of());
        }
        List<Long> modelIds = stateModelIds.stream().sorted().toList();
        Map<Long, AccountModelRouteState> statesByModel = new HashMap<>();
        for (AccountModelRouteState state : stateMapper.findByModelConfigIdsForUpdate(modelIds)) {
            statesByModel.put(state.getModelConfigId(), state);
        }
        List<ModelRoutingPolicy.Candidate> candidates = compatible.stream()
                .map(config -> new ModelRoutingPolicy.Candidate(
                        config,
                        accountsById.get(config.getVendorAccountId()),
                        statesByModel.get(config.getId())
                ))
                .filter(candidate -> candidate.state() != null)
                .toList();
        return new CandidatePool(candidates, Map.copyOf(statesByModel));
    }

    private List<String> requiredCapabilitiesForTask(AiTask task) {
        if (task == null || task.getToolId() == null) {
            return List.of();
        }
        return toolMapper.findById(task.getToolId())
                .map(capabilityService::resolveRequiredCapabilities)
                .orElse(List.of());
    }

    private boolean candidateCanReceiveNewTasks(ModelRoutingPolicy.Candidate candidate) {
        return candidate != null
                && accountCanBalance(candidate.account())
                && Boolean.TRUE.equals(candidate.modelConfig().getEnabled());
    }

    private boolean accountCanBalance(ModelVendorAccount account) {
        return account != null
                && Boolean.TRUE.equals(account.getEnabled())
                && Boolean.TRUE.equals(account.getLoadBalanceEnabled());
    }

    private boolean accountBelongsToPool(ModelVendorAccount account, Long routingPoolId) {
        return account != null
                && routingPoolId != null
                && Objects.equals(account.getRoutingPoolId(), routingPoolId);
    }

    private void reserve(AccountModelRouteState state) {
        int version = state.getVersion() == null ? 0 : state.getVersion();
        if (stateMapper.reserve(state.getId(), version) == 0) {
            throw new IllegalStateException("model route reservation conflict");
        }
        state.setVersion(version + 1);
        state.setInFlightCount((state.getInFlightCount() == null ? 0 : state.getInFlightCount()) + 1);
        state.setLastSelectedAt(LocalDateTime.now());
    }

    private void releaseFailure(AccountModelRouteState state, CircuitDecision decision) {
        if (state != null) {
            stateMapper.releaseFailure(
                    state.getId(),
                    decision.status(),
                    decision.cooldownUntil(),
                    decision.consecutiveFailures()
            );
        }
    }

    private boolean circuitAffectingFailure(RouteFailoverRequest request) {
        if (request == null) {
            return false;
        }
        String deliveryState = nullToEmpty(request.deliveryState());
        String retryScope = nullToEmpty(request.retryScope());
        if ("ACCOUNT".equalsIgnoreCase(retryScope)
                && ("NOT_SENT".equalsIgnoreCase(deliveryState)
                    || "REJECTED".equalsIgnoreCase(deliveryState))) {
            return true;
        }
        String code = (nullToEmpty(request.errorCode()) + " "
                + nullToEmpty(request.providerErrorCode())).toLowerCase(Locale.ROOT);
        return code.contains("429") || code.contains("rate_limit") || code.contains("too_many")
                || code.contains("401") || code.contains("403") || code.contains("402")
                || code.contains("auth") || code.contains("unauthorized") || code.contains("forbidden")
                || code.contains("quota") || code.contains("balance") || code.contains("credit")
                || code.contains("model_unavailable") || code.contains("model_not_found")
                || code.contains("no_available_channel") || code.contains("channel_unavailable")
                || code.contains("provider_unavailable");
    }

    private CircuitDecision circuitDecision(AccountModelRouteState state, RouteFailoverRequest request) {
        String code = ((request == null ? "" : nullToEmpty(request.errorCode())) + " "
                + (request == null ? "" : nullToEmpty(request.providerErrorCode())))
                .toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        if (code.contains("429") || code.contains("rate_limit") || code.contains("too_many")) {
            int seconds = request == null || request.retryAfterSeconds() == null
                    ? 60
                    : Math.max(1, Math.min(3600, request.retryAfterSeconds()));
            return new CircuitDecision("OPEN", now.plusSeconds(seconds), 0);
        }
        if (code.contains("401") || code.contains("403") || code.contains("402")
                || code.contains("auth") || code.contains("unauthorized") || code.contains("forbidden")
                || code.contains("quota") || code.contains("balance") || code.contains("credit")) {
            return new CircuitDecision("OPEN", now.plusMinutes(30), 0);
        }
        if (request != null && "REJECTED".equalsIgnoreCase(request.deliveryState())) {
            return new CircuitDecision("OPEN", now.plusMinutes(2), 0);
        }
        if (request != null && "NOT_SENT".equalsIgnoreCase(request.deliveryState())) {
            int previousFailures = state == null || state.getConsecutiveFailures() == null
                    ? 0
                    : Math.max(0, state.getConsecutiveFailures());
            int failures = previousFailures == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : previousFailures + 1;
            if (failures >= 2) {
                return new CircuitDecision("OPEN", now.plusMinutes(2), failures);
            }
            return new CircuitDecision("CLOSED", null, failures);
        }
        if (code.contains("model_unavailable") || code.contains("model_not_found")
                || code.contains("no_available_channel") || code.contains("channel_unavailable")
                || code.contains("provider_unavailable")) {
            return new CircuitDecision("OPEN", now.plusMinutes(5), 0);
        }
        return new CircuitDecision("CLOSED", null, 0);
    }

    private AgentModelConfig resolveReferenceModel(AiTask task) {
        return task.getModelConfigId() == null
                ? null
                : modelConfigMapper.findActiveById(task.getModelConfigId());
    }

    private BusinessException routingUnavailable(String message) {
        return new BusinessException(ErrorCode.MODEL_CALL_FAILED, message);
    }

    private AccountModelRouteState findStateForUpdate(Long modelConfigId) {
        if (modelConfigId == null) {
            return null;
        }
        List<AccountModelRouteState> states = stateMapper.findByModelConfigIdsForUpdate(List.of(modelConfigId));
        return states.isEmpty() ? null : states.get(0);
    }

    private TaskModelRouteAttempt newAttempt(Long taskId,
                                             int attemptNo,
                                             AgentModelConfig modelConfig,
                                             ModelVendorAccount account,
                                             String claimToken) {
        TaskModelRouteAttempt attempt = new TaskModelRouteAttempt();
        attempt.setTaskId(taskId);
        attempt.setAttemptNo(attemptNo);
        attempt.setModelConfigId(modelConfig.getId());
        attempt.setVendorAccountId(account.getId());
        attempt.setStatus(ACTIVE);
        attempt.setClaimToken(claimToken);
        return attempt;
    }

    private RouteFailoverRequest sanitize(RouteFailoverRequest request) {
        return new RouteFailoverRequest(
                limit(request.claimToken(), 128),
                request.routeAttemptId(),
                upper(request.deliveryState(), 32),
                upper(request.retryScope(), 32),
                upper(request.failureStage(), 64),
                limit(request.errorCode(), 64),
                limit(request.errorMessage(), 4000),
                limit(request.providerErrorCode(), 128),
                limit(request.providerRequestId(), 128),
                request.providerCharged(),
                request.retryAfterSeconds() == null ? null : Math.max(0, Math.min(86400, request.retryAfterSeconds()))
        );
    }

    private RouteFailoverRequest toRouteFailure(WorkerFailedRequest request) {
        return sanitize(new RouteFailoverRequest(
                request.claimToken(),
                null,
                request.deliveryState(),
                request.retryScope(),
                request.failureStage(),
                request.errorCode(),
                request.errorMessage(),
                request.providerErrorCode(),
                request.providerRequestId(),
                request.providerCharged(),
                request.retryAfterSeconds()
        ));
    }

    private RouteFailoverRequest toRouteFailure(AiTask task) {
        String developerSource = isBlank(task.getDeveloperMessage())
                ? task.getErrorMessage()
                : task.getDeveloperMessage();
        return sanitize(new RouteFailoverRequest(
                task.getClaimToken(),
                task.getCurrentRouteAttemptId(),
                null,
                null,
                null,
                task.getErrorCode(),
                developerSource,
                task.getProviderErrorCode(),
                task.getProviderRequestId(),
                null,
                null
        ));
    }

    private FailureContract failureContract(AiTask task,
                                            WorkerFailedRequest workerFailure,
                                            RouteFailoverRequest routeFailure) {
        String errorCode = routeFailure == null ? null : routeFailure.errorCode();
        String defaultUserMessage = TaskFailureMessage.userFacingProgressMessage(
                errorCode,
                "任务执行失败，请稍后重试"
        );
        String userMessage = ErrorMessageSanitizer.sanitizeUserMessage(
                task == null ? null : task.getUserMessage(),
                defaultUserMessage
        );

        String developerSource = task == null ? null : task.getDeveloperMessage();
        if (isBlank(developerSource) && workerFailure != null) {
            developerSource = workerFailure.developerMessage();
        }
        if (isBlank(developerSource) && routeFailure != null) {
            developerSource = routeFailure.errorMessage();
        }
        String developerMessage = ErrorMessageSanitizer.sanitizeDeveloperMessage(
                developerSource,
                "Task route attempt failed"
        );

        String failureTraceId = task == null ? null : limit(task.getFailureTraceId(), 64);
        if (failureTraceId == null && workerFailure != null) {
            failureTraceId = limit(workerFailure.failureTraceId(), 64);
        }
        if (failureTraceId == null) {
            failureTraceId = limit(MDC.get("traceId"), 64);
        }
        return new FailureContract(userMessage, developerMessage, failureTraceId);
    }

    private String normalizedOutcome(String outcome) {
        String normalized = upper(outcome, 32);
        return normalized == null ? "FAILED" : normalized;
    }

    private String upper(String value, int maxLength) {
        String limited = limit(value, maxLength);
        return limited == null ? null : limited.toUpperCase(Locale.ROOT);
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record FailoverDecision(boolean switched, String reason, Long routeAttemptId) {
        public static FailoverDecision switched(String reason, Long routeAttemptId) {
            return new FailoverDecision(true, reason, routeAttemptId);
        }

        public static FailoverDecision notSwitched(String reason, Long routeAttemptId) {
            return new FailoverDecision(false, reason, routeAttemptId);
        }
    }

    private record CandidatePool(List<ModelRoutingPolicy.Candidate> candidates,
                                 Map<Long, AccountModelRouteState> statesByModel) {
    }

    private record CircuitDecision(String status,
                                   LocalDateTime cooldownUntil,
                                   int consecutiveFailures) {
    }

    private record FailureContract(String userMessage,
                                   String developerMessage,
                                   String failureTraceId) {
    }
}
