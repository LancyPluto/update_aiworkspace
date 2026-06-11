package com.aiminilab.aitoolmarket.tool.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelConfigCredentialResolver;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.tool.dto.ToolPromptDraftFieldRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolPromptDraftRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolPromptDraftResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ToolPromptDraftService {

    private static final long MAX_PROMPT_IMAGE_BYTES = 8L * 1024L * 1024L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ModelConfigCredentialResolver credentialResolver;
    private final ModelCapabilitiesCodec capabilitiesCodec;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ToolPromptDraftService(AgentModelConfigMapper agentModelConfigMapper,
                                  ModelConfigCredentialResolver credentialResolver,
                                  ModelCapabilitiesCodec capabilitiesCodec,
                                  AppProperties appProperties,
                                  ObjectMapper objectMapper) {
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.credentialResolver = credentialResolver;
        this.capabilitiesCodec = capabilitiesCodec;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public ToolPromptDraftResponse generate(ToolPromptDraftRequest request) {
        String toolName = blankToNull(request.toolName());
        if (toolName == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请先填写工具名称");
        }
        AgentModelConfig model = selectPromptModel(request.modelConfigId());
        String visualUrl = resolveVisualReference(request.coverUrl());
        String promptText = buildDraftInstruction(request, visualUrl != null);
        String warning = null;
        String content;
        try {
            content = callOpenAiCompatible(model, promptText, visualUrl);
        } catch (PromptDraftCallException exception) {
            if (visualUrl == null) {
                throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, exception.getMessage());
            }
            warning = "封面素材未被模型接受，已自动改用纯文本信息生成。详情：" + limit(exception.getMessage(), 240);
            try {
                content = callOpenAiCompatible(model, promptText, null);
            } catch (PromptDraftCallException retryException) {
                throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, retryException.getMessage());
            }
        }
        DraftJson draft = parseDraftJson(content);
        return new ToolPromptDraftResponse(
                draft.systemPrompt(),
                draft.toolPrompt(),
                model.getId(),
                model.getModelName(),
                warning
        );
    }

    private AgentModelConfig selectPromptModel(Long preferredModelConfigId) {
        AgentModelConfig preferred = preferredModelConfigId == null
                ? null
                : agentModelConfigMapper.findAgentEnabledById(preferredModelConfigId);
        if (isPromptAssistModel(preferred)) {
            AgentModelConfig executable = credentialResolver.resolveForExecution(preferred);
            if (hasOpenAiCredentials(executable)) {
                return executable;
            }
        }

        AgentModelConfig selected = agentModelConfigMapper.findAgentEnabled().stream()
                .filter(this::isPromptAssistModel)
                .map(credentialResolver::resolveForExecution)
                .filter(this::hasOpenAiCredentials)
                .sorted((left, right) -> Boolean.compare(isPreferredOpenAiModel(right), isPreferredOpenAiModel(left)))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请先在系统配置中启用一个 OpenAI-compatible 文本模型，用于 AI 自动填写 Prompt。");
        }
        return selected;
    }

    private boolean isPromptAssistModel(AgentModelConfig config) {
        if (config == null || Boolean.FALSE.equals(config.getEnabled())) {
            return false;
        }
        String provider = defaultString(config.getProvider()).toLowerCase(Locale.ROOT);
        if (!"openai_compatible".equals(provider)) {
            return false;
        }
        List<String> capabilities = capabilitiesCodec.parse(config.getCapabilities());
        return capabilities.contains("TEXT_GENERATION");
    }

    private boolean hasOpenAiCredentials(AgentModelConfig config) {
        return config != null
                && !isBlank(config.getBaseUrl())
                && !isBlank(config.getApiKey())
                && !config.getApiKey().trim().startsWith("replace-with-");
    }

    private boolean isPreferredOpenAiModel(AgentModelConfig config) {
        String text = (defaultString(config.getProvider()) + " " + defaultString(config.getModelName()) + " "
                + defaultString(config.getDisplayName())).toLowerCase(Locale.ROOT);
        return text.contains("gpt") || text.contains("openai");
    }

    private String callOpenAiCompatible(AgentModelConfig model, String promptText, String visualUrl) {
        if (isBlank(model.getBaseUrl()) || isBlank(model.getApiKey())) {
            throw new PromptDraftCallException("OpenAI-compatible 模型缺少 Base URL 或 API Key");
        }
        try {
            Map<String, Object> userMessage = visualUrl == null
                    ? Map.<String, Object>of("role", "user", "content", promptText)
                    : Map.<String, Object>of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "text", "text", promptText),
                            Map.of("type", "image_url", "image_url", Map.of("url", visualUrl))
                    )
            );
            Map<String, Object> payload = Map.<String, Object>of(
                    "model", model.getModelName(),
                    "temperature", 0.2,
                    "messages", List.of(
                            Map.<String, Object>of(
                                    "role", "system",
                                    "content", "You are a senior AI tool prompt engineer. Return only a valid JSON object."
                            ),
                            userMessage
                    )
            );
            String body = objectMapper.writeValueAsString(payload);
            URI uri = URI.create(normalizeChatBaseUrl(model.getBaseUrl()) + "/chat/completions");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(resolveTimeout(model.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.getApiKey().trim())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new PromptDraftCallException("Prompt 自动填写调用失败：HTTP "
                        + response.statusCode() + "，" + limit(response.body(), 600));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder builder = new StringBuilder();
                content.forEach(item -> {
                    JsonNode text = item.path("text");
                    if (text.isTextual()) {
                        builder.append(text.asText());
                    }
                });
                if (builder.length() > 0) {
                    return builder.toString();
                }
            }
            throw new PromptDraftCallException("Prompt 自动填写模型返回内容为空或格式异常。");
        } catch (PromptDraftCallException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PromptDraftCallException("Prompt 自动填写响应解析失败：" + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PromptDraftCallException("Prompt 自动填写调用被中断。", exception);
        } catch (Exception exception) {
            throw new PromptDraftCallException("Prompt 自动填写调用失败：" + exception.getMessage(), exception);
        }
    }

    private String buildDraftInstruction(ToolPromptDraftRequest request, boolean includesImage) {
        String kind = defaultString(request.toolKind()).isBlank() ? "image" : request.toolKind().trim();
        String fields = fieldSummary(request.userInputs());
        String cover = blankToNull(request.coverUrl());
        return """
                请为一个即将上架的 AI 工具生成隐藏 Prompt。管理端会保存这两段 Prompt，用户端不会展示。

                工具名称：%s
                工具类型：%s
                工具描述：%s
                用户输入字段：%s
                封面素材：%s
                是否已附带封面图片内容：%s

                输出要求：
                1. 只返回 JSON，不要 Markdown，不要解释。
                2. JSON 结构必须是 {"systemPrompt":"...","toolPrompt":"..."}。
                3. systemPrompt 定义模型角色、质量要求和边界，长度控制在 60-120 个英文单词。
                4. toolPrompt 是真正交给执行模型的隐藏工具指令，必须围绕工具效果写清楚处理目标、保留/修改规则、输出质量、失败边界。
                5. toolPrompt 必须引用用户字段占位符，例如图片工具使用 {{sourceImageUrl}}，视频工具使用 {{sourceVideoUrl}}，文本工具使用 {{prompt}}。
                6. 不要承诺模型无法保证的固定结果，不要写 API Key、价格、运营说明。
                7. 优先用英文 Prompt，便于多模型稳定执行。
                """.formatted(
                toolNameOrFallback(request.toolName()),
                kind,
                defaultString(request.description()),
                fields,
                cover == null ? "未上传" : cover,
                includesImage ? "是" : "否"
        );
    }

    private String fieldSummary(List<ToolPromptDraftFieldRequest> fields) {
        if (fields == null || fields.isEmpty()) {
            return "{{sourceImageUrl}}";
        }
        return fields.stream()
                .map(field -> "%s（%s，%s）".formatted(
                        placeholder(field.fieldKey(), "field"),
                        defaultString(field.fieldName()),
                        defaultString(field.fieldType())
                ))
                .reduce((left, right) -> left + "；" + right)
                .orElse("{{sourceImageUrl}}");
    }

    private DraftJson parseDraftJson(String content) {
        String json = stripJsonFence(content);
        try {
            JsonNode root = objectMapper.readTree(json);
            String systemPrompt = text(root, "systemPrompt");
            String toolPrompt = text(root, "toolPrompt");
            if (toolPrompt == null) {
                toolPrompt = text(root, "adminPrompt");
            }
            if (systemPrompt == null || toolPrompt == null) {
                throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, "Prompt 自动填写模型返回 JSON 缺少 systemPrompt 或 toolPrompt。");
            }
            return new DraftJson(systemPrompt.trim(), toolPrompt.trim());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.MODEL_CALL_FAILED,
                    "Prompt 自动填写模型未返回合法 JSON，请重试或换一个文本模型。原始内容：" + limit(content, 500));
        }
    }

    private String resolveVisualReference(String coverUrl) {
        String value = blankToNull(coverUrl);
        if (value == null) {
            return null;
        }
        if (value.startsWith("data:image/") || value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (!value.startsWith("/generated/")) {
            return null;
        }
        String relative = value.substring("/generated/".length());
        Path file = Path.of(appProperties.getGeneratedMediaDir()).resolve(relative).normalize().toAbsolutePath();
        String mimeType = mimeType(file);
        if (!mimeType.startsWith("image/")) {
            return null;
        }
        try {
            if (!Files.exists(file) || Files.size(file) > MAX_PROMPT_IMAGE_BYTES) {
                return null;
            }
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
            return "data:" + mimeType + ";base64," + base64;
        } catch (IOException ignored) {
            return null;
        }
    }

    private String mimeType(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    private Duration resolveTimeout(Integer timeoutSeconds) {
        int seconds = timeoutSeconds == null || timeoutSeconds < 1 ? 90 : Math.max(30, timeoutSeconds);
        return Duration.ofSeconds(Math.min(180, seconds));
    }

    private String normalizeChatBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length());
        }
        return value;
    }

    private String stripJsonFence(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
            return text.substring(firstLineEnd + 1, lastFence).trim();
        }
        return text;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static String placeholder(String fieldKey, String fallback) {
        String key = defaultString(fieldKey).isBlank() ? fallback : fieldKey.trim();
        return "{{" + key + "}}";
    }

    private static String toolNameOrFallback(String toolName) {
        String value = blankToNull(toolName);
        return value == null ? "Untitled AI Tool" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String limit(String value, int maxLength) {
        String text = defaultString(value);
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 16)) + "...[truncated]";
    }

    private record DraftJson(String systemPrompt, String toolPrompt) {
    }

    private static class PromptDraftCallException extends RuntimeException {
        PromptDraftCallException(String message) {
            super(message);
        }

        PromptDraftCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
