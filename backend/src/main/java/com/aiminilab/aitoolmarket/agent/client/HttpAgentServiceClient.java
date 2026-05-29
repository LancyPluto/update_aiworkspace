package com.aiminilab.aitoolmarket.agent.client;

import com.aiminilab.aitoolmarket.agent.dto.AgentFileParseResult;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRouteDebugResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.MarketChatCompletionRequest;
import com.aiminilab.aitoolmarket.agent.dto.MarketChatCompletionResponse;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.config.TraceIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Component
public class HttpAgentServiceClient implements AgentServiceClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(75);

    private final AppProperties appProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpAgentServiceClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    @Override
    public void executeRun(Long runId) {
        postInternal("/internal/v1/agent/runs/" + runId + "/execute", "{}");
    }

    @Override
    public void confirmTool(Long runId, String toolCode) {
        postInternal("/internal/v1/agent/runs/" + runId + "/confirm-tool", "{\"toolCode\":\"" + escapeJson(toolCode) + "\"}");
    }

    @Override
    public AgentFileParseResult parseFile(String filename, String contentType, byte[] content) {
        String jsonBody = toJson(Map.of(
                "filename", filename == null ? "upload.bin" : filename,
                "contentType", contentType == null ? "application/octet-stream" : contentType,
                "contentBase64", java.util.Base64.getEncoder().encodeToString(content == null ? new byte[0] : content)
        ));
        String response = postInternal("/internal/v1/files/parse", jsonBody);
        try {
            AgentFileParseResult result = objectMapper.readValue(response, AgentFileParseResult.class);
            return result == null ? AgentFileParseResult.fromText(filename, "") : result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse agent-service file parse response", exception);
        }
    }

    @Override
    public MarketChatCompletionResponse marketChatCompletion(MarketChatCompletionRequest request) {
        if (!appProperties.getAgent().isEnabled()) {
            return new MarketChatCompletionResponse("");
        }
        String response = postInternal("/internal/v1/market/chat/completions", toJson(request));
        try {
            MarketChatCompletionResponse result = objectMapper.readValue(response, MarketChatCompletionResponse.class);
            return result == null ? new MarketChatCompletionResponse("") : result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse agent-service market chat response", exception);
        }
    }

    @Override
    public AgentModelConfigTestResponse testModelConfig(AgentModelConfigRequest request) {
        if (!appProperties.getAgent().isEnabled()) {
            return new AgentModelConfigTestResponse(
                    false,
                    request.provider(),
                    request.modelName(),
                    0L,
                    "后端已关闭 Agent 集成（application.yml 中 app.agent.enabled=false），无法向 agent-service 发起测试。"
                            + "请开启该项并确保 agent-service 可访问后再试。",
                    ""
            );
        }
        String response = postInternal("/internal/v1/agent/model-config/test", toJson(request));
        try {
            return objectMapper.readValue(response, AgentModelConfigTestResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse agent-service model config test response", exception);
        }
    }

    @Override
    public AdminAgentRouteDebugResponse debugRoute(InternalAgentRunContextResponse context) {
        String response = postInternal("/internal/v1/agent/route-debug", toJson(context));
        try {
            return objectMapper.readValue(response, AdminAgentRouteDebugResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse agent-service route debug response", exception);
        }
    }

    private String postInternal(String path, String jsonBody) {
        if (!appProperties.getAgent().isEnabled()) {
            return "{}";
        }
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create(trimTrailingSlash(appProperties.getAgent().getServiceBaseUrl()) + path);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = UUID.randomUUID().toString();
        String signature = sign("POST", uri.getRawPath(), timestamp, nonce, body);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Nonce", nonce)
                .header("X-Internal-Signature", signature)
                .header(TraceIdFilter.REQUEST_ID_HEADER, currentTraceId())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Agent service rejected " + path
                        + " with HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while notifying agent service at " + path, exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not notify agent service at " + path, exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize agent service request", exception);
        }
    }

    private String sign(String method, String path, String timestamp, String nonce, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String content = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appProperties.getInternalApiToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign agent service request", exception);
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:8090";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }
}
