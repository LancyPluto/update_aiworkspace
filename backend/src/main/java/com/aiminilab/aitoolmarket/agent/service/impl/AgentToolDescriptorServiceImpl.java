package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolFieldDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolDescriptorExtension;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class AgentToolDescriptorServiceImpl implements AgentToolDescriptorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentToolDescriptorServiceImpl.class);

    private final ToolMapper toolMapper;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final AgentToolDescriptorExtensionMapper extensionMapper;
    private final ObjectMapper objectMapper;

    public AgentToolDescriptorServiceImpl(ToolMapper toolMapper,
                                          ToolFieldItemMapper toolFieldItemMapper,
                                          AgentToolDescriptorExtensionMapper extensionMapper,
                                          ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.extensionMapper = extensionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AgentToolDescriptorResponse> listAvailableToolsForUser(Long userId) {
        return toolMapper.findTools(true, null, null, null, 100, 0)
                .stream()
                .filter(tool -> {
                    Optional<AgentToolDescriptorExtension> ext = extensionMapper.findByToolCode(tool.getToolCode());
                    return ext.map(AgentToolDescriptorExtension::getAgentEnabled).orElse(true);
                })
                .map(this::toDescriptor)
                .toList();
    }

    @Override
    public AgentToolDescriptorResponse getToolForAgent(Long userId, String toolCode) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TOOL_NOT_AVAILABLE, "工具不可用"));
        return toDescriptor(tool);
    }

    private AgentToolDescriptorResponse toDescriptor(AiTool tool) {
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
                        field.sortOrder()
                ))
                .toList();

        AgentToolDescriptorExtension ext = extensionMapper.findByToolCode(tool.getToolCode()).orElse(null);
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
            property.put("type", jsonType(field.fieldType()));
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
            properties.set(field.fieldKey(), property);
            if (Boolean.TRUE.equals(field.required())) {
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
