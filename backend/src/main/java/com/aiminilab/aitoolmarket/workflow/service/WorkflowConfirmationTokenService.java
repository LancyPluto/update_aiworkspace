package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowConfirmation;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowConfirmationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkflowConfirmationTokenService {

    private static final String TOKEN_VERSION = "wc1";

    private final WorkflowConfirmationMapper confirmationMapper;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public WorkflowConfirmationTokenService(WorkflowConfirmationMapper confirmationMapper,
                                            ObjectMapper objectMapper,
                                            AppProperties appProperties) {
        this.confirmationMapper = confirmationMapper;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    public WorkflowConfirmation issue(WorkflowRun run,
                                      WorkflowRunStep step,
                                      JsonNode nodeParameters) {
        WorkflowConfirmation confirmation = new WorkflowConfirmation();
        confirmation.setRunId(run.getId());
        confirmation.setStepId(step.getId());
        confirmation.setUserId(run.getUserId());
        confirmation.setParameterHash(parameterHash(run, step));
        confirmation.setAllowedActionsJson(writeJson(allowedActions(nodeParameters)));
        confirmation.setStatus("PENDING");
        confirmation.setExpiresAt(LocalDateTime.now().withNano(0).plusMinutes(
                appProperties.getWorkflow().getConfirmation().getTtlMinutes()
        ));
        String placeholderHash = sha256Hex("pending:" + UUID.randomUUID());
        confirmation.setTokenHash(placeholderHash);
        confirmationMapper.insert(confirmation);

        String rawToken = rawToken(confirmation, run, step);
        String tokenHash = tokenHash(rawToken);
        if (confirmationMapper.replaceTokenHash(confirmation.getId(), placeholderHash, tokenHash) != 1) {
            throw new IllegalStateException("Workflow confirmation token hash could not be persisted");
        }
        confirmation.setTokenHash(tokenHash);
        return confirmation;
    }

    public String rawToken(WorkflowConfirmation confirmation,
                           WorkflowRun run,
                           WorkflowRunStep step) {
        String payload = canonicalPayload(confirmation, run, step);
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
        return TOKEN_VERSION + "." + confirmation.getId() + "." + signature;
    }

    public boolean verify(String rawToken,
                          WorkflowConfirmation confirmation,
                          WorkflowRun run,
                          WorkflowRunStep step) {
        if (rawToken == null || confirmation == null || run == null || step == null) {
            return false;
        }
        byte[] suppliedHash = tokenHash(rawToken).getBytes(StandardCharsets.US_ASCII);
        byte[] storedHash = confirmation.getTokenHash().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(suppliedHash, storedHash)) {
            return false;
        }
        byte[] expected = rawToken(confirmation, run, step).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(rawToken.getBytes(StandardCharsets.US_ASCII), expected);
    }

    public String tokenHash(String rawToken) {
        return sha256Hex(rawToken == null ? "" : rawToken);
    }

    public String parameterHash(WorkflowRun run, WorkflowRunStep step) {
        return sha256Hex(lengthPrefixed(run.getInputJson())
                + lengthPrefixed(run.getContextJson())
                + lengthPrefixed(String.valueOf(run.getWorkflowVersionId()))
                + lengthPrefixed(step.getNodeId())
                + lengthPrefixed(step.getInputJson()));
    }

    public List<String> allowedActions(JsonNode nodeParameters) {
        if (nodeParameters == null || !nodeParameters.path("allowedActions").isArray()) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        for (JsonNode action : nodeParameters.path("allowedActions")) {
            if (!action.isTextual()) {
                continue;
            }
            String normalized = action.asText().trim().toUpperCase(Locale.ROOT);
            if (!normalized.isBlank() && !actions.contains(normalized)) {
                actions.add(normalized);
            }
        }
        return List.copyOf(actions);
    }

    public List<String> readAllowedActions(WorkflowConfirmation confirmation) {
        if (confirmation == null || confirmation.getAllowedActionsJson() == null) {
            return List.of();
        }
        try {
            JsonNode actions = objectMapper.readTree(confirmation.getAllowedActionsJson());
            if (!actions.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode action : actions) {
                if (action.isTextual() && !action.asText().isBlank()) {
                    result.add(action.asText().trim().toUpperCase(Locale.ROOT));
                }
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String canonicalPayload(WorkflowConfirmation confirmation,
                                    WorkflowRun run,
                                    WorkflowRunStep step) {
        long expiresAt = confirmation.getExpiresAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        return lengthPrefixed(String.valueOf(confirmation.getId()))
                + lengthPrefixed(String.valueOf(confirmation.getRunId()))
                + lengthPrefixed(String.valueOf(confirmation.getStepId()))
                + lengthPrefixed(String.valueOf(confirmation.getUserId()))
                + lengthPrefixed(String.valueOf(run.getRootTaskId()))
                + lengthPrefixed(String.valueOf(run.getWorkflowVersionId()))
                + lengthPrefixed(String.valueOf(step.getCurrentAttemptId()))
                + lengthPrefixed(confirmation.getParameterHash())
                + lengthPrefixed(confirmation.getAllowedActionsJson())
                + lengthPrefixed(String.valueOf(expiresAt));
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(effectiveSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign workflow confirmation token", exception);
        }
    }

    private String effectiveSecret() {
        String configured = appProperties.getWorkflow().getConfirmation().getHmacSecret();
        if (!configured.isBlank()) {
            return configured;
        }
        if (appProperties.isProductionEnvironment()) {
            throw new IllegalStateException("Workflow confirmation HMAC secret is not configured");
        }
        return appProperties.getJwtSecret();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize workflow confirmation data", exception);
        }
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash workflow confirmation data", exception);
        }
    }

    private String lengthPrefixed(String value) {
        String normalized = value == null ? "" : value;
        return normalized.length() + ":" + normalized;
    }
}
