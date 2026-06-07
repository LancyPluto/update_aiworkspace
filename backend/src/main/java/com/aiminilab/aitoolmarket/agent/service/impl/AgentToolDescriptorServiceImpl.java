package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentToolAccessResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolFieldDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolPickerItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolAccessRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolDescriptorExtension;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolDescriptorExtensionMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
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
    private final AgentModelConfigMapper modelConfigMapper;
    private final ObjectMapper objectMapper;

    public AgentToolDescriptorServiceImpl(ToolMapper toolMapper,
                                          ToolFieldItemMapper toolFieldItemMapper,
                                          AgentToolDescriptorExtensionMapper extensionMapper,
                                          AgentModelConfigMapper modelConfigMapper,
                                          ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.extensionMapper = extensionMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AgentToolDescriptorResponse> listAvailableToolsForUser(Long userId) {
        List<AiTool> tools = toolMapper.findTools(true, null, null, null, AGENT_AVAILABLE_TOOL_LIMIT, 0);
        Map<String, AgentToolDescriptorExtension> extensions = findExtensionsByToolCode(tools);
        return tools.stream()
                .filter(tool -> {
                    AgentToolDescriptorExtension ext = extensions.get(tool.getToolCode());
                    return ext == null || isAgentReadable(ext);
                })
                .map(tool -> toDescriptor(tool, extensions.get(tool.getToolCode())))
                .toList();
    }

    @Override
    public List<AgentToolPickerItemResponse> listPickerToolsForUser(Long userId) {
        List<AiTool> tools = toolMapper.findTools(true, null, null, null, AGENT_AVAILABLE_TOOL_LIMIT, 0);
        Map<String, AgentToolDescriptorExtension> extensions = findExtensionsByToolCode(tools);
        return tools.stream()
                .filter(tool -> {
                    AgentToolDescriptorExtension ext = extensions.get(tool.getToolCode());
                    return ext == null || isAgentReadable(ext);
                })
                .map(tool -> new AgentToolPickerItemResponse(
                        tool.getToolCode(),
                        tool.getToolName(),
                        tool.getDescription(),
                        normalizeOutputType(tool.getOutputModality()),
                        tool.getCoverUrl(),
                        tool.getEstimatedCreditCost()
                ))
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
        List<AgentToolFieldDescriptorResponse> fieldDescriptors = fields.stream()
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
                tool.getEstimatedCreditCost(),
                toInputSchema(fields),
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

    private ObjectNode toInputSchema(List<ToolFieldResponse> fields) {
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();
        schema.put("type", "object");
        for (ToolFieldResponse field : fields) {
            ObjectNode property = objectMapper.createObjectNode();
            if ("multi_image".equalsIgnoreCase(field.fieldType())) {
                property.put("type", "array");
                ObjectNode itemSchema = objectMapper.createObjectNode();
                itemSchema.put("type", "string");
                property.set("items", itemSchema);
            } else {
                property.put("type", jsonType(field.fieldType()));
            }
            property.put("title", field.fieldName());
            if (field.placeholder() != null && !field.placeholder().isBlank()) {
                property.put("description", field.placeholder());
            }
            if ("select".equalsIgnoreCase(field.fieldType()) && field.options() != null && field.options().isArray()) {
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

    private String jsonType(String fieldType) {
        if ("number".equalsIgnoreCase(fieldType)) {
            return "number";
        }
        if ("integer".equalsIgnoreCase(fieldType)) {
            return "integer";
        }
        if ("boolean".equalsIgnoreCase(fieldType)) {
            return "boolean";
        }
        return "string";
    }
}
