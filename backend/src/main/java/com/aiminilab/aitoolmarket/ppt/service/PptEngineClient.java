package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.config.PptEngineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PptEngineClient {

    private final PptEngineProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final RestClient healthRestClient;
    private volatile boolean cachedHealthy;
    private volatile long healthCacheUntilNanos;
    private volatile long circuitOpenUntilNanos;
    private int consecutiveHealthFailures;

    public PptEngineClient(PptEngineProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getEngine().getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getEngine().getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(properties.getEngine().getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
        SimpleClientHttpRequestFactory healthFactory = new SimpleClientHttpRequestFactory();
        healthFactory.setConnectTimeout(Math.min(properties.getEngine().getConnectTimeoutMs(), 2000));
        healthFactory.setReadTimeout(2000);
        this.healthRestClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(properties.getEngine().getBaseUrl()))
                .requestFactory(healthFactory)
                .build();
    }

    public JsonNode createProject(Map<String, Object> body) {
        return post("/api/projects", body, true);
    }

    public JsonNode getProject(String projectId) {
        return get("/api/projects/" + projectId);
    }

    public void deleteProject(String projectId) {
        delete("/api/projects/" + projectId);
    }

    public JsonNode postProjectAction(String projectId, String actionPath, Map<String, Object> body) {
        return post("/api/projects/" + projectId + actionPath, body == null ? Map.of() : body, false);
    }

    public JsonNode getProjectAction(String projectId, String actionPath) {
        return get("/api/projects/" + projectId + actionPath);
    }

    public JsonNode getSubmission(String projectId, String idempotencyKey) {
        try {
            return get("/api/projects/" + projectId + "/submissions/" + idempotencyKey);
        } catch (PptEngineResponseException exception) {
            if (exception.getStatusCode() == 404) {
                return null;
            }
            throw exception;
        }
    }

    public JsonNode putProjectAction(String projectId, String actionPath, Map<String, Object> body) {
        return exchange("PUT", "/api/projects/" + projectId + actionPath, body, false);
    }

    public JsonNode updateProject(String projectId, Map<String, Object> body) {
        return exchange("PUT", "/api/projects/" + projectId, body == null ? Map.of() : body, false);
    }

    public void deleteProjectAction(String projectId, String actionPath) {
        String path = "/api/projects/" + projectId + (actionPath.startsWith("/") ? actionPath : "/" + actionPath);
        delete(path);
    }

    public JsonNode postRenovation(MultiValueMap<String, Object> formData) {
        return exchangeMultipart("/api/projects/renovation", formData);
    }

    public JsonNode postProjectMultipart(String projectId, String actionPath, MultiValueMap<String, Object> formData) {
        String path = "/api/projects/" + projectId + actionPath;
        return exchangeMultipart(path, formData);
    }

    public byte[] downloadFile(String enginePath) {
        try {
            URI uri = buildEngineFileUri(enginePath);
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw engineStatusError(response);
                    })
                    .body(byte[].class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw engineError(exception, false);
        }
    }

    /** 按路径段编码，避免中文等非 ASCII 文件名导致引擎 404/400 */
    private URI buildEngineFileUri(String enginePath) {
        String normalized = enginePath == null ? "" : enginePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(normalizeBaseUrl(properties.getEngine().getBaseUrl()));
        Arrays.stream(normalized.split("/"))
                .filter(segment -> !segment.isBlank())
                .forEach(builder::pathSegment);
        return builder.build().encode(StandardCharsets.UTF_8).toUri();
    }

    public void updateSettings(Map<String, Object> body) {
        put("/api/settings", body == null ? Map.of() : body);
    }

    public synchronized boolean healthCheck() {
        long now = System.nanoTime();
        if (now < healthCacheUntilNanos) {
            return cachedHealthy;
        }
        if (now < circuitOpenUntilNanos) {
            return false;
        }
        try {
            healthRestClient.get()
                    .uri("/readyz")
                    .retrieve()
                    .toBodilessEntity();
            cachedHealthy = true;
            consecutiveHealthFailures = 0;
            circuitOpenUntilNanos = 0;
            healthCacheUntilNanos = now + TimeUnit.SECONDS.toNanos(3);
            return true;
        } catch (Exception exception) {
            cachedHealthy = false;
            consecutiveHealthFailures++;
            healthCacheUntilNanos = now + TimeUnit.SECONDS.toNanos(2);
            if (consecutiveHealthFailures >= 3) {
                circuitOpenUntilNanos = now + TimeUnit.SECONDS.toNanos(15);
            }
            return false;
        }
    }

    private JsonNode put(String path, Map<String, Object> body) {
        return exchange("PUT", path, body, false);
    }

    private JsonNode post(String path, Map<String, Object> body, boolean allow201) {
        return exchange("POST", path, body, allow201);
    }

    private JsonNode get(String path) {
        return exchange("GET", path, null, false);
    }

    private void delete(String path) {
        try {
            restClient.delete()
                    .uri(path)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw engineError(exception, true);
        }
    }

    private JsonNode exchange(String method, String path, Map<String, Object> body, boolean allow201) {
        try {
            RestClient.RequestBodySpec spec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(path);
            byte[] responseBody;
            Map<String, Object> payload = body == null ? Map.of() : body;
            if ("POST".equals(method) || "PUT".equals(method)) {
                responseBody = spec.contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .onStatus(status -> status.isError() && !(allow201 && status.value() == 201),
                                (request, response) -> {
                                    throw engineStatusError(response);
                                })
                        .body(byte[].class);
            } else {
                responseBody = spec.retrieve()
                        .onStatus(status -> status.isError() && !(allow201 && status.value() == 201),
                                (request, response) -> {
                                    throw engineStatusError(response);
                                })
                        .body(byte[].class);
            }
            return parseSuccessBody(decodeBody(responseBody));
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            boolean mutating = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
            throw engineError(exception, mutating);
        }
    }

    private JsonNode exchangeMultipart(String path, MultiValueMap<String, Object> formData) {
        try {
            byte[] responseBody = restClient.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(formData)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw engineStatusError(response);
                    })
                    .body(byte[].class);
            return parseSuccessBody(decodeBody(responseBody));
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw engineError(exception, true);
        }
    }

    private JsonNode parseSuccessBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("success") && !root.path("success").asBoolean(true)) {
                String message = root.path("error").path("message").asText(root.path("message").asText("引擎返回失败"));
                throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, message);
            }
            if (root.has("data")) {
                return root.path("data");
            }
            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "引擎响应解析失败");
        }
    }

    private String decodeBody(byte[] responseBody) {
        return responseBody == null || responseBody.length == 0
                ? null
                : new String(responseBody, StandardCharsets.UTF_8);
    }

    private BusinessException engineStatusError(org.springframework.http.client.ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readAllBytes();
            String body = bytes.length > 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
            return engineErrorFromBody(body, response.getStatusCode().value());
        } catch (IOException exception) {
            return new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "引擎请求失败");
        }
    }

    private BusinessException engineError(Exception exception, boolean mutating) {
        if (exception instanceof RestClientResponseException responseException) {
            return engineErrorFromBody(responseException.getResponseBodyAsString(), responseException.getStatusCode().value());
        }
        boolean definitelyNotSubmitted = hasCause(exception, ConnectException.class)
                || hasCause(exception, UnknownHostException.class)
                || hasCause(exception, HttpConnectTimeoutException.class)
                || isConnectTimeout(exception);
        boolean outcomeUnknown = mutating && !definitelyNotSubmitted;
        String message = outcomeUnknown
                ? "PPT 引擎响应中断，提交结果待确认"
                : "无法连接 PPT 引擎: " + exception.getMessage();
        return new PptEngineTransportException(message, outcomeUnknown);
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConnectTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase().contains("connect")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private BusinessException engineErrorFromBody(String body, int statusCode) {
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                String message = root.path("error").path("message").asText(root.path("message").asText("引擎请求失败"));
                return new PptEngineResponseException(statusCode, message);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return new PptEngineResponseException(statusCode, "引擎请求失败: HTTP " + statusCode);
    }

    public static MultiValueMap<String, Object> singleFilePart(String fieldName, MultipartFile file) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        if (file != null && !file.isEmpty()) {
            form.add(fieldName, file.getResource());
        }
        return form;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://127.0.0.1:5000";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
