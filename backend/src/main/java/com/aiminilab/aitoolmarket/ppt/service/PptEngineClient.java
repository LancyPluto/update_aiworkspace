package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.config.PptEngineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

@Component
public class PptEngineClient {

    private final PptEngineProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PptEngineClient(PptEngineProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(properties.getEngine().getBaseUrl()))
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
            throw engineError(exception);
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

    public boolean healthCheck() {
        try {
            restClient.get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception exception) {
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
            throw engineError(exception);
        }
    }

    private JsonNode exchange(String method, String path, Map<String, Object> body, boolean allow201) {
        try {
            RestClient.RequestBodySpec spec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(path);
            String responseBody;
            Map<String, Object> payload = body == null ? Map.of() : body;
            if ("POST".equals(method) || "PUT".equals(method)) {
                responseBody = spec.contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .onStatus(status -> status.isError() && !(allow201 && status.value() == 201),
                                (request, response) -> {
                                    throw engineStatusError(response);
                                })
                        .body(String.class);
            } else {
                responseBody = spec.retrieve()
                        .onStatus(status -> status.isError() && !(allow201 && status.value() == 201),
                                (request, response) -> {
                                    throw engineStatusError(response);
                                })
                        .body(String.class);
            }
            return parseSuccessBody(responseBody);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw engineError(exception);
        }
    }

    private JsonNode exchangeMultipart(String path, MultiValueMap<String, Object> formData) {
        try {
            String responseBody = restClient.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(formData)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw engineStatusError(response);
                    })
                    .body(String.class);
            return parseSuccessBody(responseBody);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw engineError(exception);
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

    private BusinessException engineStatusError(org.springframework.http.client.ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readAllBytes();
            String body = bytes.length > 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
            return engineErrorFromBody(body, response.getStatusCode().value());
        } catch (IOException exception) {
            return new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "引擎请求失败");
        }
    }

    private BusinessException engineError(Exception exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return engineErrorFromBody(responseException.getResponseBodyAsString(), responseException.getStatusCode().value());
        }
        return new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "无法连接 PPT 引擎: " + exception.getMessage());
    }

    private BusinessException engineErrorFromBody(String body, int statusCode) {
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                String message = root.path("error").path("message").asText(root.path("message").asText("引擎请求失败"));
                return new BusinessException(ErrorCode.PPT_ENGINE_ERROR, message);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "引擎请求失败: HTTP " + statusCode);
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
