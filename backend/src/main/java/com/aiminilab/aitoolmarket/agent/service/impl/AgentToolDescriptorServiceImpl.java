package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentToolAccessResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolFieldDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolPickerItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolAccessRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolDescriptorExtension;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolPreference;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolDescriptorExtensionMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolPreferenceMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class AgentToolDescriptorServiceImpl implements AgentToolDescriptorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentToolDescriptorServiceImpl.class);
    private static final int AGENT_AVAILABLE_TOOL_LIMIT = 1000;
    private static final String HEALTH_FAILED = "FAILED";
    private static final String HEALTH_UNKNOWN = "UNKNOWN";

    private final ToolMapper toolMapper;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final AgentToolDescriptorExtensionMapper extensionMapper;
    private final AgentToolPreferenceMapper preferenceMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final TaskCreditEstimateService taskCreditEstimateService;
    private final ObjectMapper objectMapper;

    public AgentToolDescriptorServiceImpl(ToolMapper toolMapper,
                                          ToolFieldItemMapper toolFieldItemMapper,
                                          AgentToolDescriptorExtensionMapper extensionMapper,
                                          AgentToolPreferenceMapper preferenceMapper,
                                          AgentModelConfigMapper modelConfigMapper,
                                          TaskCreditEstimateService taskCreditEstimateService,
                                          ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.extensionMapper = extensionMapper;
        this.preferenceMapper = preferenceMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.taskCreditEstimateService = taskCreditEstimateService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AgentToolDescriptorResponse> listAvailableToolsForUser(Long userId) {
        List<AiTool> tools = toolMapper.findTools(true, null, null, null, AGENT_AVAILABLE_TOOL_LIMIT, 0);
        Map<String, AgentToolDescriptorExtension> extensions = findExtensionsByToolCode(tools);
        Set<String> disabledToolCodes = disabledToolCodes(userId);
        return tools.stream()
                .filter(tool -> {
                    AgentToolDescriptorExtension ext = extensions.get(tool.getToolCode());
                    return (ext == null || isAgentReadable(ext)) && !disabledToolCodes.contains(tool.getToolCode());
                })
                .map(tool -> toDescriptor(tool, extensions.get(tool.getToolCode())))
                .toList();
    }

    @Override
    public List<AgentToolPickerItemResponse> listPickerToolsForUser(Long userId) {
        List<AiTool> tools = toolMapper.findTools(true, null, null, null, AGENT_AVAILABLE_TOOL_LIMIT, 0);
        Map<String, AgentToolDescriptorExtension> extensions = findExtensionsByToolCode(tools);
        Map<String, AgentToolPreference> preferences = preferencesByToolCode(userId);
        return tools.stream()
                .filter(tool -> {
                    AgentToolDescriptorExtension ext = extensions.get(tool.getToolCode());
                    return ext == null || isAgentReadable(ext);
                })
                .map(tool -> {
                    AgentToolPreference preference = preferences.get(tool.getToolCode());
                    boolean disabled = preference != null && Boolean.TRUE.equals(preference.getDisabled());
                    return new AgentToolPickerItemResponse(
                            tool.getToolCode(),
                            tool.getToolName(),
                            tool.getDescription(),
                            normalizeOutputType(tool.getOutputModality()),
                            tool.getCoverUrl(),
                            taskCreditEstimateService.estimateUserFacingTaskCredits(tool),
                            preference != null && Boolean.TRUE.equals(preference.getAutoCallEnabled()) && !disabled,
                            disabled
                    );
                })
                .toList();
    }

    @Override
    public AgentToolDescriptorResponse getToolForAgent(Long userId, String toolCode) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TOOL_NOT_AVAILABLE, "工具不可用"));
        AgentToolDescriptorExtension ext = extensionMapper.findByToolCode(tool.getToolCode()).orElse(null);
        if (ext != null && !isAgentReadable(ext)) {
            throw new BusinessException(ErrorCode.AGENT_TOOL_NOT_AVAILABLE, "Tool is disabled for Agent");
        }
        AgentToolPreference preference = preferenceMapper.findByUserIdAndToolCode(userId, tool.getToolCode());
        if (preference != null && Boolean.TRUE.equals(preference.getDisabled())) {
            throw new BusinessException(ErrorCode.AGENT_TOOL_NOT_AVAILABLE, "Tool is disabled by user");
        }
        return toDescriptor(tool, ext);
    }

    @Override
    public List<AdminAgentToolAccessResponse> listAdminToolAccess() {
        List<AiTool> tools = toolMapper.findTools(true, null, null, null, AGENT_AVAILABLE_TOOL_LIMIT, 0);
        Map<String, AgentToolDescriptorExtension> extensions = findExtensionsByToolCode(tools);
        return tools.stream()
                .map(tool -> {
                    AgentToolDescriptorExtension ext = extensions.get(tool.getToolCode());
                    return adminAccessResponse(tool, ext);
                })
                .toList();
    }

    @Override
    @Transactional
    public AdminAgentToolAccessResponse updateAdminToolAccess(String toolCode, UpdateAgentToolAccessRequest request) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TOOL_NOT_AVAILABLE, "Tool is not online"));
        AgentToolDescriptorExtension ext = extensionMapper.findByToolCode(tool.getToolCode()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (ext == null) {
            ext = new AgentToolDescriptorExtension();
            ext.setToolId(tool.getId());
            ext.setToolCode(tool.getToolCode());
            ext.setAgentRecommendable(true);
            ext.setAgentAutoCallable(false);
            ext.setConfirmationPolicy("auto");
            ext.setRiskLevel("low");
            ext.setOutputType(normalizeOutputType(tool.getOutputModality()));
            ext.setCreatedAt(now);
        }
        ext.setAgentEnabled(Boolean.TRUE.equals(request.agentEnabled()));
        if (Boolean.TRUE.equals(request.agentEnabled()) && HEALTH_FAILED.equalsIgnoreCase(ext.getHealthStatus())) {
            ext.setHealthStatus(HEALTH_UNKNOWN);
            ext.setHealthMessage(null);
            ext.setHealthCheckedAt(now);
            ext.setAgentRecommendable(true);
        }
        ext.setUpdatedAt(now);
        if (ext.getId() == null) {
            extensionMapper.insert(ext);
        } else {
            extensionMapper.updateById(ext);
        }
        return adminAccessResponse(tool, ext);
    }

    @Override
    @Transactional
    public void markToolHealth(String toolCode, String healthStatus, String healthMessage) {
        if (toolCode == null || toolCode.isBlank()) {
            return;
        }
        AiTool tool = toolMapper.findOnlineByCode(toolCode).orElse(null);
        if (tool == null) {
            return;
        }
        AgentToolDescriptorExtension ext = extensionMapper.findByToolCode(tool.getToolCode()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (ext == null) {
            ext = new AgentToolDescriptorExtension();
            ext.setToolId(tool.getId());
            ext.setToolCode(tool.getToolCode());
            ext.setAgentEnabled(true);
            ext.setAgentRecommendable(true);
            ext.setAgentAutoCallable(false);
            ext.setConfirmationPolicy("auto");
            ext.setRiskLevel("low");
            ext.setOutputType(normalizeOutputType(tool.getOutputModality()));
            ext.setCreatedAt(now);
        }
        String normalizedStatus = normalizeHealthStatus(healthStatus);
        ext.setHealthStatus(normalizedStatus);
        ext.setHealthMessage(limitText(healthMessage, 512));
        ext.setHealthCheckedAt(now);
        ext.setUpdatedAt(now);
        if (HEALTH_FAILED.equals(normalizedStatus)) {
            ext.setAgentRecommendable(false);
            ext.setAgentAutoCallable(false);
        } else if (Boolean.TRUE.equals(ext.getAgentEnabled())) {
            ext.setAgentRecommendable(true);
        }
        if (ext.getId() == null) {
            extensionMapper.insert(ext);
        } else {
            extensionMapper.updateById(ext);
        }
    }

    private AgentToolDescriptorResponse toDescriptor(AiTool tool) {
        return toDescriptor(tool, extensionMapper.findByToolCode(tool.getToolCode()).orElse(null));
    }

    private AgentToolDescriptorResponse toDescriptor(AiTool tool, AgentToolDescriptorExtension ext) {
        List<ToolFieldResponse> fields = toolFieldItemMapper.findActiveFields(tool.getId()).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
        List<AgentToolFieldDescriptorResponse> fieldDescriptors = isGptImageTool(tool.getToolCode())
                ? imageV2LiteFieldDescriptors()
                : fields.stream()
                        .map(field -> new AgentToolFieldDescriptorResponse(
                                field.fieldKey(),
                                field.fieldName(),
                                field.fieldType(),
                                field.placeholder(),
                                field.options(),
                                field.required(),
                                field.executionRequired(),
                                field.userRequired(),
                                field.defaultValue(),
                                field.agentFillStrategy(),
                                field.riskLevel(),
                                field.sortOrder()
                        ))
                        .toList();

        boolean autoCallable = ext != null ? Boolean.TRUE.equals(ext.getAgentAutoCallable()) : false;

        return new AgentToolDescriptorResponse(
                tool.getToolCode(),
                tool.getToolName(),
                tool.getDescription(),
                taskCreditEstimateService.estimateUserFacingTaskCredits(tool),
                toInputSchema(tool.getToolCode(), fields),
                autoCallable,
                fieldDescriptors,
                loadHints(tool.getToolCode(), ext)
        );
    }

    private Map<String, AgentToolDescriptorExtension> findExtensionsByToolCode(List<AiTool> tools) {
        List<String> toolCodes = tools.stream()
                .map(AiTool::getToolCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        if (toolCodes.isEmpty()) {
            return Map.of();
        }
        return extensionMapper.selectByToolCodes(toolCodes).stream()
                .collect(Collectors.toMap(
                        AgentToolDescriptorExtension::getToolCode,
                        extension -> extension,
                        (left, right) -> left
                ));
    }

    private Map<String, AgentToolPreference> preferencesByToolCode(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        return preferenceMapper.findByUserId(userId).stream()
                .filter(preference -> preference.getToolCode() != null && !preference.getToolCode().isBlank())
                .collect(Collectors.toMap(
                        AgentToolPreference::getToolCode,
                        preference -> preference,
                        (left, right) -> left
                ));
    }

    private Set<String> disabledToolCodes(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return preferenceMapper.findByUserId(userId).stream()
                .filter(preference -> Boolean.TRUE.equals(preference.getDisabled()))
                .map(AgentToolPreference::getToolCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
    }

    private boolean agentEnabled(AgentToolDescriptorExtension ext) {
        return ext == null || Boolean.TRUE.equals(ext.getAgentEnabled());
    }

    private String healthStatus(AgentToolDescriptorExtension ext) {
        if (ext == null || ext.getHealthStatus() == null || ext.getHealthStatus().isBlank()) {
            return HEALTH_UNKNOWN;
        }
        return ext.getHealthStatus();
    }

    private String healthMessage(AgentToolDescriptorExtension ext) {
        return ext == null ? null : ext.getHealthMessage();
    }

    private LocalDateTime healthCheckedAt(AgentToolDescriptorExtension ext) {
        return ext == null ? null : ext.getHealthCheckedAt();
    }

    private boolean isAgentReadable(AgentToolDescriptorExtension ext) {
        return !Boolean.FALSE.equals(ext.getAgentEnabled())
                && !HEALTH_FAILED.equalsIgnoreCase(ext.getHealthStatus());
    }

    private String normalizeHealthStatus(String healthStatus) {
        if (healthStatus == null || healthStatus.isBlank()) {
            return HEALTH_UNKNOWN;
        }
        String normalized = healthStatus.trim().toUpperCase();
        if (!Set.of("HEALTHY", "UNKNOWN", HEALTH_FAILED).contains(normalized)) {
            return HEALTH_UNKNOWN;
        }
        return normalized;
    }

    private String limitText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 16)) + "...[truncated]";
    }

    private String normalizeOutputType(String outputModality) {
        if (outputModality == null || outputModality.isBlank()) {
            return "text";
        }
        return outputModality.trim().toLowerCase();
    }

    private AdminAgentToolAccessResponse adminAccessResponse(AiTool tool, AgentToolDescriptorExtension ext) {
        AgentModelConfig modelConfig = tool.getModelConfigId() == null
                ? null
                : modelConfigMapper.findActiveById(tool.getModelConfigId());
        return AdminAgentToolAccessResponse.from(
                tool,
                agentEnabled(ext),
                healthStatus(ext),
                healthMessage(ext),
                healthCheckedAt(ext),
                modelConfig == null ? null : modelConfig.getProvider(),
                modelConfig == null ? null : modelConfig.getBaseUrl(),
                modelConfig == null ? null : modelConfig.getTimeoutSeconds(),
                modelConfig == null ? null : extraAuthInt(modelConfig.getExtraAuthJson(), "connectTimeoutSeconds"),
                modelConfig == null ? null : extraAuthInt(modelConfig.getExtraAuthJson(), "readTimeoutSeconds"),
                modelConfig == null ? null : proxyConfigured(modelConfig.getExtraAuthJson())
        );
    }

    private Integer extraAuthInt(String value, String key) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            JsonNode child = node.get(key);
            return child == null || !child.canConvertToInt() ? null : child.asInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean proxyConfigured(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            JsonNode proxyMode = node.get("proxyMode");
            if (proxyMode != null && "enabled".equalsIgnoreCase(proxyMode.asText(""))) {
                return true;
            }
            JsonNode proxyUrl = node.get("proxyUrl");
            return proxyUrl != null && !proxyUrl.asText("").isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Object> loadHints(String toolCode, AgentToolDescriptorExtension ext) {
        if (ext != null && ext.getKeywordsJson() != null && !ext.getKeywordsJson().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(ext.getKeywordsJson());
                if (node.isArray()) {
                    List<String> keywords = StreamSupport.stream(node.spliterator(), false)
                            .filter(JsonNode::isTextual)
                            .map(JsonNode::asText)
                            .toList();
                    return Map.of("keywords", keywords);
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse keywords_json for tool_code={}: {}", toolCode, e.getMessage());
            }
        }
        return switch (toolCode) {
            case "xiaohongshu_copywriting" -> Map.of("keywords", List.of("小红书", "种草", "笔记"));
            case "moments_copywriting_generator" -> Map.of("keywords", List.of("朋友圈", "微信朋友圈", "私域文案"));
            case "product_title_optimizer" -> Map.of("keywords", List.of("商品标题", "标题优化", "电商标题"));
            case "wechat_longform_generator" -> Map.of("keywords", List.of("公众号", "微信长文", "长文"));
            case "social_media_comment_insights_agent" -> Map.of("keywords", List.of("社交媒体评论", "小红书评论", "抖音评论", "评论分析", "用户洞察", "产品建议"));
            default -> Map.of("keywords", List.of());
        };
    }

    private ObjectNode toInputSchema(String toolCode, List<ToolFieldResponse> fields) {
        if (isGptImageTool(toolCode)) {
            return imageV2LiteInputSchema();
        }
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();
        schema.put("type", "object");
        for (ToolFieldResponse field : fields) {
            ObjectNode property = objectMapper.createObjectNode();
            if ("multi_image".equalsIgnoreCase(field.fieldType()) || "multi_video".equalsIgnoreCase(field.fieldType())) {
                property.put("type", "array");
                ObjectNode itemSchema = objectMapper.createObjectNode();
                itemSchema.put("type", "string");
                property.set("items", itemSchema);
            } else if ("omni_video_list".equalsIgnoreCase(field.fieldType())) {
                property.put("type", "array");
                ObjectNode itemSchema = objectMapper.createObjectNode();
                itemSchema.put("type", "object");
                ObjectNode itemProperties = objectMapper.createObjectNode();
                itemProperties.putObject("video_url").put("type", "string");
                itemProperties.putObject("refer_type").put("type", "string");
                itemProperties.putObject("keep_original_sound").put("type", "string");
                itemSchema.set("properties", itemProperties);
                property.set("items", itemSchema);
            } else if ("subject_element_list".equalsIgnoreCase(field.fieldType())) {
                property.put("type", "array");
                ObjectNode itemSchema = objectMapper.createObjectNode();
                itemSchema.put("type", "object");
                property.set("items", itemSchema);
            } else {
                property.put("type", jsonType(field.fieldType()));
            }
            property.put("title", field.fieldName());
            if (field.placeholder() != null && !field.placeholder().isBlank()) {
                property.put("description", field.placeholder());
            }
            if (supportsEnum(field.fieldType()) && field.options() != null && field.options().isArray()) {
                ArrayNode enumValues = objectMapper.createArrayNode();
                field.options().forEach(option -> {
                    if (option.isTextual()) {
                        enumValues.add(option.asText());
                    } else if (option.has("value")) {
                        enumValues.add(option.get("value").asText());
                    }
                });
                if (!enumValues.isEmpty()) {
                    property.set("enum", enumValues);
                }
            }
            if (field.defaultValue() != null && !field.defaultValue().isBlank()) {
                property.put("default", field.defaultValue());
            }
            property.put("x-user-required", Boolean.TRUE.equals(field.userRequired() == null ? field.required() : field.userRequired()));
            property.put("x-agent-fill-strategy", field.agentFillStrategy() == null || field.agentFillStrategy().isBlank()
                    ? (Boolean.TRUE.equals(field.userRequired() == null ? field.required() : field.userRequired()) ? "ask_user" : "default")
                    : field.agentFillStrategy());
            property.put("x-risk-level", field.riskLevel() == null || field.riskLevel().isBlank() ? "LOW" : field.riskLevel());
            properties.set(field.fieldKey(), property);
            if (Boolean.TRUE.equals(field.executionRequired() == null ? field.required() : field.executionRequired())) {
                required.add(field.fieldKey());
            }
        }
        schema.set("properties", properties);
        schema.set("required", required);
        return schema;
    }

    private ObjectNode imageV2LiteInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();
        schema.put("type", "object");
        schema.put("$id", "https://aidesu.ai/schemas/agent-image-generation-v2-lite.json");
        schema.put("additionalProperties", false);
        schema.set("allOf", imageV2LiteConditionals());
        required.add("operation");
        properties.set("operation", imageV2StringProperty(
                "Operation",
                "generate=全新生成；edit=修改已有图；variation=基于已有图做变体；composite=多图融合。",
                new String[]{"generate", "edit", "variation", "composite"},
                "generate",
                "derive"
        ));
        properties.set("generation_prompt", imageV2StringProperty(
                "Generation prompt",
                "仅用于 generate/composite/variation 的完整生图提示词。edit 操作不要使用该字段。",
                null,
                null,
                "derive"
        ));
        properties.set("base_image_ref", imageV2StringProperty(
                "Base image reference",
                "被编辑或续作的底图引用。可填 latest_generated_image.url、generated_images[0].url、@图片1、fileId、assetKey 或 URL。edit/variation 必填。",
                null,
                null,
                "llm"
        ));
        properties.set("base_prompt", imageV2StringProperty(
                "Base prompt",
                "edit/variation 必填。必须原样复制 SessionState 中底图对应的 prompt，不要改写、总结或翻译。",
                null,
                null,
                "llm"
        ));
        properties.set("modification_prompt", imageV2StringProperty(
                "Modification prompt",
                "edit/variation 必填。只写本轮新增修改要求，例如换背景、保留某张脸、调色、修手。不要重写完整场景。",
                null,
                null,
                "llm"
        ));
        properties.set("negative_prompt", imageV2StringProperty(
                "Negative prompt",
                "负向约束，例如脸崩、畸形手、水印、文字、风格跑偏。",
                null,
                null,
                "derive"
        ));
        properties.set("references", imageV2ReferencesProperty());
        properties.set("aspect_ratio", imageV2StringProperty(
                "Aspect ratio",
                "目标画面比例。",
                new String[]{"auto", "1:1", "4:3", "3:4", "16:9", "9:16", "21:9"},
                "auto",
                "default"
        ));
        ObjectNode count = objectMapper.createObjectNode();
        count.put("type", "integer");
        count.put("title", "Count");
        count.put("description", "生成张数。");
        count.put("minimum", 1);
        count.put("maximum", 4);
        count.put("default", 1);
        count.put("x-user-required", false);
        count.put("x-agent-fill-strategy", "default");
        count.put("x-risk-level", "LOW");
        properties.set("count", count);
        properties.set("routing_notes", imageV2StringProperty(
                "Routing notes",
                "简短审计说明，例如：图1管脸，图2管动作构图。",
                null,
                null,
                "derive"
        ));
        schema.set("properties", properties);
        schema.set("required", required);
        return schema;
    }

    private ObjectNode imageV2StringProperty(String title, String description, String[] enumValues, String defaultValue, String strategy) {
        ObjectNode property = objectMapper.createObjectNode();
        property.put("type", "string");
        property.put("title", title);
        property.put("description", description);
        if (enumValues != null && enumValues.length > 0) {
            ArrayNode values = objectMapper.createArrayNode();
            for (String value : enumValues) {
                values.add(value);
            }
            property.set("enum", values);
        }
        if (defaultValue != null && !defaultValue.isBlank()) {
            property.put("default", defaultValue);
        }
        property.put("x-user-required", false);
        property.put("x-agent-fill-strategy", strategy == null || strategy.isBlank() ? "llm" : strategy);
        property.put("x-risk-level", "LOW");
        return property;
    }

    private ObjectNode imageV2ReferencesProperty() {
        ObjectNode references = objectMapper.createObjectNode();
        references.put("type", "array");
        references.put("title", "Typed references");
        references.put("description", "语义化参考图。LLM 只需要指定核心角色；强度和模型参数由后端按 role 默认处理。");
        references.put("x-agent-fill-strategy", "llm");
        references.put("x-user-required", false);
        references.put("x-risk-level", "LOW");
        ObjectNode itemSchema = objectMapper.createObjectNode();
        itemSchema.put("type", "object");
        itemSchema.put("additionalProperties", false);
        ObjectNode itemProperties = objectMapper.createObjectNode();
        itemProperties.putObject("id").put("type", "string").put("description", "稳定 ID，例如 face_ref_1、style_ref_1、pose_ref_1。");
        ObjectNode role = itemProperties.putObject("role");
        role.put("type", "string");
        ArrayNode roleEnum = objectMapper.createArrayNode();
        roleEnum.add("face_ref");
        roleEnum.add("identity_ref");
        roleEnum.add("style_ref");
        roleEnum.add("pose_ref");
        roleEnum.add("composition_ref");
        roleEnum.add("controlnet_pose_ref");
        roleEnum.add("background_ref");
        roleEnum.add("object_ref");
        roleEnum.add("supplemental_ref");
        role.set("enum", roleEnum);
        itemProperties.putObject("source_ref").put("type", "string").put("description", "图片指针或 URL，例如 [当前参考图_1]、fileId:123、assetKey:xxx、latest_generated_image.url。");
        itemProperties.putObject("notes").put("type", "string").put("description", "自然语言补充，例如：只参考构图，不参考画风；保留这张脸；仅参考动作。");
        itemSchema.set("properties", itemProperties);
        ArrayNode itemRequired = objectMapper.createArrayNode();
        itemRequired.add("id");
        itemRequired.add("role");
        itemRequired.add("source_ref");
        itemSchema.set("required", itemRequired);
        references.set("items", itemSchema);
        return references;
    }

    private List<AgentToolFieldDescriptorResponse> imageV2LiteFieldDescriptors() {
        List<AgentToolFieldDescriptorResponse> fields = new ArrayList<>();
        fields.add(imageV2Field("operation", "Operation", "select", "generate=全新生成；edit=修改已有图；variation=基于已有图做变体；composite=多图融合。", "generate", "derive", 1));
        fields.add(imageV2Field("generation_prompt", "Generation prompt", "textarea", "仅用于 generate/composite/variation 的完整生图提示词。edit 操作不要使用该字段。", null, "derive", 2));
        fields.add(imageV2Field("base_image_ref", "Base image reference", "text", "edit/variation 的底图引用。", null, "llm", 3));
        fields.add(imageV2Field("base_prompt", "Base prompt", "textarea", "edit/variation 的底图视觉 prompt。", null, "llm", 4));
        fields.add(imageV2Field("modification_prompt", "Modification prompt", "textarea", "edit/variation 的本轮视觉变化。", null, "llm", 5));
        fields.add(imageV2Field("negative_prompt", "Negative prompt", "textarea", "负向约束。", null, "derive", 6));
        fields.add(imageV2Field("references", "Typed references", "json", "语义化参考图数组。", null, "llm", 7));
        fields.add(imageV2Field("aspect_ratio", "Aspect ratio", "select", "目标画面比例。", "auto", "default", 8));
        fields.add(imageV2Field("count", "Count", "integer", "生成张数。", "1", "default", 9));
        fields.add(imageV2Field("routing_notes", "Routing notes", "textarea", "简短审计说明。", null, "derive", 10));
        return fields;
    }

    private AgentToolFieldDescriptorResponse imageV2Field(
            String key,
            String name,
            String type,
            String description,
            String defaultValue,
            String strategy,
            int sortOrder
    ) {
        return new AgentToolFieldDescriptorResponse(
                key,
                name,
                type,
                description,
                null,
                false,
                false,
                false,
                defaultValue,
                strategy,
                "LOW",
                sortOrder
        );
    }

    private ArrayNode imageV2LiteConditionals() {
        ArrayNode allOf = objectMapper.createArrayNode();
        allOf.add(imageOperationConditional(new String[]{"edit", "variation"}, new String[]{"base_image_ref", "base_prompt", "modification_prompt"}));
        allOf.add(imageOperationConditional(new String[]{"generate", "composite"}, new String[]{"generation_prompt"}));
        return allOf;
    }

    private ObjectNode imageOperationConditional(String[] operations, String[] requiredFields) {
        ObjectNode conditional = objectMapper.createObjectNode();
        ObjectNode ifNode = objectMapper.createObjectNode();
        ObjectNode ifProperties = objectMapper.createObjectNode();
        ObjectNode operation = objectMapper.createObjectNode();
        ArrayNode operationEnum = objectMapper.createArrayNode();
        for (String value : operations) {
            operationEnum.add(value);
        }
        operation.set("enum", operationEnum);
        ifProperties.set("operation", operation);
        ifNode.set("properties", ifProperties);
        ArrayNode ifRequired = objectMapper.createArrayNode();
        ifRequired.add("operation");
        ifNode.set("required", ifRequired);

        ObjectNode thenNode = objectMapper.createObjectNode();
        ArrayNode thenRequired = objectMapper.createArrayNode();
        for (String value : requiredFields) {
            thenRequired.add(value);
        }
        thenNode.set("required", thenRequired);

        conditional.set("if", ifNode);
        conditional.set("then", thenNode);
        return conditional;
    }

    private boolean isGptImageTool(String toolCode) {
        String code = toolCode == null ? "" : toolCode.trim().toLowerCase();
        return code.contains("gpt_image") || code.contains("gpt-image") || code.contains("openai_image");
    }

    private String jsonType(String fieldType) {
        if ("number".equalsIgnoreCase(fieldType) || "slider".equalsIgnoreCase(fieldType)) {
            return "number";
        }
        if ("integer".equalsIgnoreCase(fieldType)) {
            return "integer";
        }
        if ("boolean".equalsIgnoreCase(fieldType) || "checkbox".equalsIgnoreCase(fieldType)) {
            return "boolean";
        }
        return "string";
    }

    private boolean supportsEnum(String fieldType) {
        return "select".equalsIgnoreCase(fieldType)
                || "radio".equalsIgnoreCase(fieldType)
                || "aspect_ratio".equalsIgnoreCase(fieldType);
    }
}
