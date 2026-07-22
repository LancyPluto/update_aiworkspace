package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.dto.ProviderCallbackEventResponse;
import com.aiminilab.aitoolmarket.task.dto.ProviderCallbackRegistrationRequest;
import com.aiminilab.aitoolmarket.task.dto.ProviderCallbackRegistrationResponse;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.entity.ProviderCallbackInboxEvent;
import com.aiminilab.aitoolmarket.task.entity.ProviderCallbackRegistration;
import com.aiminilab.aitoolmarket.task.mapper.ProviderCallbackMapper;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.ProviderCallbackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class ProviderCallbackServiceImpl implements ProviderCallbackService {
    private static final int MAX_CALLBACK_BYTES = 256 * 1024;

    private final ProviderCallbackMapper callbackMapper;
    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;
    private final int tokenTtlHours;

    public ProviderCallbackServiceImpl(
            ProviderCallbackMapper callbackMapper,
            TaskMapper taskMapper,
            ObjectMapper objectMapper,
            @Value("${app.provider-callback.public-base-url:https://wlcloudai.com}") String publicBaseUrl,
            @Value("${app.provider-callback.token-ttl-hours:24}") int tokenTtlHours
    ) {
        this.callbackMapper = callbackMapper;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.publicBaseUrl = normalizePublicBaseUrl(publicBaseUrl);
        this.tokenTtlHours = Math.max(1, Math.min(tokenTtlHours, 168));
    }

    @Override
    @Transactional
    public ProviderCallbackRegistrationResponse register(
            Long taskId,
            ProviderCallbackRegistrationRequest request
    ) {
        AiTask task = requireActiveClaim(taskId, request.claimToken());
        String providerCode = normalizeProvider(request.providerCode());
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(tokenTtlHours);

        ProviderCallbackRegistration registration = new ProviderCallbackRegistration();
        registration.setProviderCode(providerCode);
        registration.setTaskId(taskId);
        registration.setRouteAttemptId(task.getCurrentRouteAttemptId());
        registration.setTokenHash(sha256(token));
        registration.setExpiresAt(expiresAt);
        callbackMapper.insertRegistration(registration);

        return new ProviderCallbackRegistrationResponse(
                registration.getId(),
                taskId,
                providerCode,
                publicBaseUrl + "/api/v1/provider-callbacks/suno/music/" + token,
                expiresAt
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderCallbackEventResponse latest(Long taskId, String providerCode, String claimToken) {
        requireActiveClaim(taskId, claimToken);
        ProviderCallbackInboxEvent event = callbackMapper.findLatestTerminal(
                taskId,
                normalizeProvider(providerCode)
        );
        if (event == null) {
            return null;
        }
        try {
            return new ProviderCallbackEventResponse(
                    event.getId(),
                    event.getTaskId(),
                    event.getProviderCode(),
                    event.getProviderTaskId(),
                    event.getCallbackType(),
                    event.getProviderStatusCode(),
                    objectMapper.readTree(event.getPayloadJson()),
                    event.getReceivedAt()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Could not parse provider callback payload", exception);
        }
    }

    @Override
    @Transactional
    public void receiveSuno(String callbackToken, JsonNode payload) {
        if (callbackToken == null || !callbackToken.matches("[a-f0-9]{64}")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Invalid callback token");
        }
        ProviderCallbackRegistration registration = callbackMapper.findActiveRegistration(sha256(callbackToken));
        if (registration == null || !"suno_music".equals(registration.getProviderCode())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Callback registration not found or expired");
        }
        if (payload == null || !payload.isObject()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Callback payload must be a JSON object");
        }

        String payloadJson = payload.toString();
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_CALLBACK_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Callback payload is too large");
        }
        JsonNode data = payload.path("data");
        String providerTaskId = text(data, "task_id", "taskId");
        if (providerTaskId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Callback payload is missing data.task_id");
        }
        if (callbackMapper.bindProviderTask(registration.getId(), providerTaskId) == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Callback task id does not match registration");
        }

        String callbackType = text(data, "callbackType", "callback_type").toLowerCase(Locale.ROOT);
        if (callbackType.isBlank()) {
            callbackType = payload.path("code").asInt(500) == 200 ? "complete" : "error";
        }
        ProviderCallbackInboxEvent event = new ProviderCallbackInboxEvent();
        event.setRegistrationId(registration.getId());
        event.setProviderCode("suno_music");
        event.setTaskId(registration.getTaskId());
        event.setProviderTaskId(providerTaskId);
        event.setCallbackType(callbackType);
        event.setProviderStatusCode(payload.path("code").isInt() ? payload.path("code").asInt() : null);
        event.setPayloadJson(payloadJson);
        event.setPayloadSha256(sha256(payloadJson));
        try {
            callbackMapper.insertInbox(event);
        } catch (DuplicateKeyException ignored) {
            // Provider callbacks are at-least-once. Persisting one identical event is sufficient.
        }
        if ("complete".equals(callbackType) || "error".equals(callbackType)) {
            callbackMapper.markRegistrationCompleted(registration.getId());
        }
    }

    private AiTask requireActiveClaim(Long taskId, String claimToken) {
        AiTask task = taskMapper.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "Task not found"));
        String normalizedClaim = claimToken == null ? "" : claimToken.trim();
        if (!"PROCESSING".equals(task.getStatus())
                || normalizedClaim.isBlank()
                || !normalizedClaim.equals(task.getClaimToken())
                || task.getLeaseUntil() == null
                || task.getLeaseUntil().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "Task execution lease does not match");
        }
        return task;
    }

    private static String normalizeProvider(String providerCode) {
        String normalized = providerCode == null ? "" : providerCode.trim().toLowerCase(Locale.ROOT);
        if ("suno".equals(normalized)) {
            return "suno_music";
        }
        if (!"suno_music".equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Unsupported callback provider");
        }
        return normalized;
    }

    private static String normalizePublicBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://localhost")) {
            throw new IllegalArgumentException("Provider callback public base URL must use HTTPS");
        }
        return normalized.replaceAll("/+$", "");
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
