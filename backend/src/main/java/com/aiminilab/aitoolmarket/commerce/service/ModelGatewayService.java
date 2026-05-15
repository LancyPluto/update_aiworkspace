package com.aiminilab.aitoolmarket.commerce.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ModelGatewayService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ModelGatewayService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public ModelGatewayResult chat(Long sessionId, Long poolId, Long nodeId, String userPrompt) {
        Map<String, Object> node = jdbcTemplate.queryForMap("""
                SELECT n.*, p.provider, p.model_name AS pool_model_name
                FROM model_channel_nodes n
                JOIN model_channel_pools p ON p.id = n.pool_id
                WHERE n.id = ? AND n.pool_id = ?
                """, nodeId, poolId);
        String apiKey = stringValue(node.get("api_key"));
        String baseUrl = stringValue(node.get("base_url"));
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            String content = "Demo response from " + node.get("node_code") + ": " + userPrompt;
            return result(content, recentMessages(sessionId), null, "demo");
        }

        String protocol = stringValue(node.get("provider_protocol"));
        String model = stringValue(node.get("model_name"));
        if (model.isBlank()) {
            model = stringValue(node.get("pool_model_name"));
        }
        List<Map<String, String>> history = recentMessages(sessionId);
        int timeoutSeconds = timeoutSeconds(node.get("timeout_seconds"));
        if (protocol.contains("anthropic")) {
            return callAnthropicCompatible(baseUrl, apiKey, model, history, timeoutSeconds);
        }
        return callOpenAiCompatible(baseUrl, apiKey, model, history, timeoutSeconds);
    }

    public void healthCheck(Long nodeId) {
        Map<String, Object> node = jdbcTemplate.queryForMap("""
                SELECT n.*, p.provider, p.model_name AS pool_model_name
                FROM model_channel_nodes n
                JOIN model_channel_pools p ON p.id = n.pool_id
                WHERE n.id = ?
                """, nodeId);
        String apiKey = stringValue(node.get("api_key"));
        String baseUrl = stringValue(node.get("base_url"));
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            return;
        }

        String protocol = stringValue(node.get("provider_protocol"));
        String model = stringValue(node.get("model_name"));
        if (model.isBlank()) {
            model = stringValue(node.get("pool_model_name"));
        }
        List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "ping"));
        int timeoutSeconds = timeoutSeconds(node.get("timeout_seconds"));
        if (protocol.contains("anthropic")) {
            callAnthropicCompatible(baseUrl, apiKey, model, messages, timeoutSeconds);
            return;
        }
        callOpenAiCompatible(baseUrl, apiKey, model, messages, timeoutSeconds);
    }

    private ModelGatewayResult callOpenAiCompatible(String baseUrl, String apiKey, String model,
                                                    List<Map<String, String>> messages, int timeoutSeconds) {
        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", 1,
                    "stream", false,
                    "messages", messages
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveEndpoint(baseUrl, "/chat/completions")))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isMissingNode() && !content.asText().isBlank()) {
                return result(content.asText(), messages, root.path("usage"), "openai_compatible");
            }
            throw new ModelGatewayException("RESPONSE_FORMAT", true,
                    "model response missing choices[0].message.content");
        } catch (ModelGatewayException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new ModelGatewayException("TIMEOUT", true, "model request timed out after " + timeoutSeconds + "s");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException("INTERRUPTED", true, "model request interrupted");
        } catch (Exception exception) {
            throw new ModelGatewayException("NETWORK", true, "model request failed: " + exception.getMessage());
        }
    }

    private ModelGatewayResult callAnthropicCompatible(String baseUrl, String apiKey, String model,
                                                       List<Map<String, String>> messages, int timeoutSeconds) {
        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", 2048,
                    "stream", false,
                    "messages", messages.stream().filter(message -> !"system".equals(message.get("role"))).toList()
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveEndpoint(baseUrl, "/v1/messages")))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("content").path(0).path("text");
            if (!content.isMissingNode() && !content.asText().isBlank()) {
                return result(content.asText(), messages, root.path("usage"), "anthropic_compatible");
            }
            throw new ModelGatewayException("RESPONSE_FORMAT", true, "model response missing content[0].text");
        } catch (ModelGatewayException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new ModelGatewayException("TIMEOUT", true, "model request timed out after " + timeoutSeconds + "s");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException("INTERRUPTED", true, "model request interrupted");
        } catch (Exception exception) {
            throw new ModelGatewayException("NETWORK", true, "model request failed: " + exception.getMessage());
        }
    }

    private List<Map<String, String>> recentMessages(Long sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT role, content_text
                FROM model_chat_messages
                WHERE session_id = ?
                ORDER BY id DESC
                LIMIT 20
                """, sessionId);
        List<Map<String, String>> messages = new ArrayList<>();
        for (int index = rows.size() - 1; index >= 0; index--) {
            Map<String, Object> row = rows.get(index);
            String role = "ASSISTANT".equals(row.get("role")) ? "assistant" : "user";
            messages.add(Map.of("role", role, "content", stringValue(row.get("content_text"))));
        }
        return messages;
    }

    private static void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        String body = response.body() == null ? "" : response.body();
        throw new ModelGatewayException(errorCategory(response.statusCode()), response.statusCode() >= 400,
                "model provider returned " + response.statusCode() + ": " + abbreviate(body, 600));
    }

    private static String resolveEndpoint(String baseUrl, String suffix) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (trimmed.endsWith("/chat/completions") || trimmed.endsWith("/messages")) {
            return trimmed;
        }
        return trimmed + suffix;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private ModelGatewayResult result(String content, List<Map<String, String>> messages, JsonNode usage, String protocol) {
        Integer promptTokens = readInt(usage, "prompt_tokens", "input_tokens");
        Integer completionTokens = readInt(usage, "completion_tokens", "output_tokens");
        Integer totalTokens = readInt(usage, "total_tokens");
        if (promptTokens == null) {
            promptTokens = estimateTokens(messages.stream().map(message -> message.get("content")).toList());
        }
        if (completionTokens == null) {
            completionTokens = estimateTokens(List.of(content));
        }
        if (totalTokens == null) {
            totalTokens = promptTokens + completionTokens;
        }
        String metadataJson = metadataJson(protocol, usage == null || usage.isMissingNode());
        return new ModelGatewayResult(content, promptTokens, completionTokens, totalTokens,
                estimateCostCents(totalTokens), metadataJson);
    }

    private String metadataJson(String protocol, boolean estimatedTokens) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "protocol", protocol,
                    "tokensEstimated", estimatedTokens,
                    "costEstimated", true,
                    "costPolicy", "default_zero_until_pricing_configured"
            ));
        } catch (Exception ignored) {
            return "{\"costPolicy\":\"default_zero_until_pricing_configured\"}";
        }
    }

    private static Integer readInt(JsonNode usage, String... names) {
        if (usage == null || usage.isMissingNode()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = usage.path(name);
            if (value.isNumber()) {
                return value.asInt();
            }
        }
        return null;
    }

    private static int estimateTokens(List<String> values) {
        int characters = values.stream().filter(value -> value != null).mapToInt(String::length).sum();
        return Math.max(1, (int) Math.ceil(characters / 4.0));
    }

    private static int estimateCostCents(Integer totalTokens) {
        if (totalTokens == null || totalTokens <= 0) {
            return 0;
        }
        return 0;
    }

    private static int timeoutSeconds(Object value) {
        if (value instanceof Number number) {
            return Math.min(300, Math.max(5, number.intValue()));
        }
        return 90;
    }

    private static String errorCategory(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return "AUTH";
        }
        if (statusCode == 408) {
            return "TIMEOUT";
        }
        if (statusCode == 429) {
            return "RATE_LIMIT";
        }
        if (statusCode >= 500) {
            return "PROVIDER_5XX";
        }
        return "PROVIDER_4XX";
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
