package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.ToolStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.dto.CreateFieldSchemaRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptVersionRequest;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptVersionResponse;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateRequest;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldSchema;
import com.aiminilab.aitoolmarket.tool.entity.ToolPrompt;
import com.aiminilab.aitoolmarket.tool.entity.ToolPromptVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class ToolServiceImpl implements ToolService {

    private static final Set<String> ALLOWED_FIELD_TYPES = Set.of("text", "textarea", "select", "number");

    private final ToolMapper toolMapper;
    private final ObjectMapper objectMapper;

    public ToolServiceImpl(ToolMapper toolMapper, ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ToolCategoryResponse> categories() {
        return toolMapper.findActiveCategories().stream()
                .map(ToolCategoryResponse::from)
                .toList();
    }

    @Override
    public PageResponse<ToolSummaryResponse> userTools() {
        List<ToolSummaryResponse> list = toolMapper.findTools(true).stream()
                .map(ToolSummaryResponse::from)
                .toList();
        return new PageResponse<>(list, list.size());
    }

    @Override
    public ToolDetailResponse userToolDetail(String toolCode) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
        ToolSummaryResponse summary = ToolSummaryResponse.from(tool);
        return ToolDetailResponse.of(summary, fields(tool.getId()));
    }

    @Override
    public PageResponse<ToolSummaryResponse> adminTools() {
        List<ToolSummaryResponse> list = toolMapper.findTools(false).stream()
                .map(ToolSummaryResponse::from)
                .toList();
        return new PageResponse<>(list, list.size());
    }

    @Override
    @Transactional
    public ToolSummaryResponse createTool(UpsertToolRequest request, Long operatorId) {
        AiTool tool = fromRequest(request);
        Long toolId = toolMapper.insertTool(tool, operatorId);
        Long schemaId = toolMapper.createActiveDefaultSchema(toolId, operatorId);
        toolMapper.createDefaultFields(schemaId);
        Long promptId = toolMapper.createPrompt(toolId, "default", "默认 Prompt");
        Long versionId = toolMapper.createActivePromptVersion(promptId, "v1",
                "请根据用户输入参数生成结果。", operatorId);
        toolMapper.setPromptActiveVersion(promptId, versionId);
        return findToolSummary(toolId);
    }

    @Override
    public ToolSummaryResponse updateTool(Long toolId, UpsertToolRequest request, Long operatorId) {
        ensureToolExists(toolId);
        toolMapper.updateTool(toolId, fromRequest(request), operatorId);
        return findToolSummary(toolId);
    }

    @Override
    @Transactional
    public ToolSummaryResponse publishTool(Long toolId, Long operatorId) {
        AiTool tool = ensureToolExists(toolId);
        validateBeforePublish(tool);
        toolMapper.updateToolStatus(toolId, ToolStatus.ONLINE, operatorId);
        return findToolSummary(toolId);
    }

    @Override
    public ToolSummaryResponse offlineTool(Long toolId, Long operatorId) {
        ensureToolExists(toolId);
        toolMapper.updateToolStatus(toolId, ToolStatus.OFFLINE, operatorId);
        return findToolSummary(toolId);
    }

    @Override
    public List<ToolFieldResponse> adminFields(Long toolId) {
        ensureToolExists(toolId);
        return fields(toolId);
    }

    @Override
    @Transactional
    public List<ToolFieldResponse> updateFields(Long toolId, UpdateToolFieldsRequest request) {
        ensureToolExists(toolId);
        validateFieldTypes(request.fields());
        Long schemaId = toolMapper.findActiveSchemaId(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具字段配置不存在"));
        toolMapper.replaceActiveFields(schemaId, request.fields().stream()
                .map(this::toFieldItem)
                .toList());
        return fields(toolId);
    }

    @Override
    public List<FieldSchemaResponse> fieldSchemas(Long toolId) {
        ensureToolExists(toolId);
        return toolMapper.findSchemas(toolId).stream()
                .map(this::toFieldSchemaResponse)
                .toList();
    }

    @Override
    @Transactional
    public FieldSchemaResponse createFieldSchema(Long toolId, CreateFieldSchemaRequest request, Long operatorId) {
        ensureToolExists(toolId);
        validateFieldTypes(request.fields());
        Long schemaId = toolMapper.createFieldSchema(toolId, request.schemaVersion(), operatorId);
        toolMapper.insertSchemaFields(schemaId, request.fields().stream()
                .map(this::toFieldItem)
                .toList());
        return toolMapper.findSchemaById(schemaId)
                .map(this::toFieldSchemaResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "字段 Schema 创建失败"));
    }

    @Override
    @Transactional
    public FieldSchemaResponse publishFieldSchema(Long schemaId) {
        ToolFieldSchema schema = toolMapper.findSchemaById(schemaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "字段 Schema 不存在"));
        toolMapper.inactivateSchemas(schema.getToolId());
        toolMapper.activateSchema(schemaId);
        return toolMapper.findSchemaById(schemaId)
                .map(this::toFieldSchemaResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "字段 Schema 发布失败"));
    }

    @Override
    public List<PromptResponse> prompts(Long toolId) {
        ensureToolExists(toolId);
        return toolMapper.findPrompts(toolId).stream()
                .map(this::toPromptResponse)
                .toList();
    }

    @Override
    public List<PromptVersionResponse> promptVersions(Long promptId) {
        toolMapper.findPromptById(promptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "Prompt 不存在"));
        return toolMapper.findPromptVersions(promptId).stream()
                .map(this::toPromptVersionResponse)
                .toList();
    }

    @Override
    public PromptResponse createPrompt(Long toolId, CreatePromptRequest request) {
        ensureToolExists(toolId);
        Long promptId = toolMapper.createPrompt(toolId, request.promptCode(), request.promptName());
        return toolMapper.findPromptById(promptId)
                .map(this::toPromptResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "Prompt 创建失败"));
    }

    @Override
    public PromptVersionResponse createPromptVersion(Long promptId, CreatePromptVersionRequest request, Long operatorId) {
        toolMapper.findPromptById(promptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "Prompt 不存在"));
        String outputFormat = request.outputFormat() == null || request.outputFormat().isBlank()
                ? "MARKDOWN"
                : request.outputFormat();
        Long versionId = toolMapper.createPromptVersion(
                promptId,
                request.versionNo(),
                request.systemPrompt(),
                request.userPromptTemplate(),
                outputFormat,
                operatorId
        );
        return toolMapper.findPromptVersionById(versionId)
                .map(this::toPromptVersionResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "Prompt 版本创建失败"));
    }

    @Override
    public TestGenerateResponse testGenerate(Long promptVersionId, TestGenerateRequest request) {
        ToolPromptVersion version = toolMapper.findPromptVersionById(promptVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "Prompt 版本不存在"));
        String rendered = renderTemplate(version.getUserPromptTemplate(), request.params());
        return new TestGenerateResponse(rendered);
    }

    @Override
    @Transactional
    public PromptVersionResponse publishPromptVersion(Long promptVersionId) {
        ToolPromptVersion version = toolMapper.findPromptVersionById(promptVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "Prompt 版本不存在"));
        toolMapper.inactivatePromptVersions(version.getPromptId());
        toolMapper.activatePromptVersion(promptVersionId);
        toolMapper.setPromptActiveVersion(version.getPromptId(), promptVersionId);
        return toolMapper.findPromptVersionById(promptVersionId)
                .map(this::toPromptVersionResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "Prompt 版本发布失败"));
    }

    private void validateBeforePublish(AiTool tool) {
        if (tool.getToolName() == null || tool.getToolName().isBlank()
                || tool.getCategoryId() == null
                || tool.getEstimatedCreditCost() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工具基础信息不完整");
        }
        if (tool.getEstimatedCreditCost() < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预估算力消耗不能小于 0");
        }
        Long schemaId = toolMapper.findActiveSchemaId(tool.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "工具缺少 ACTIVE 字段 Schema"));
        if (toolMapper.findFieldsBySchemaId(schemaId).isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工具 ACTIVE 字段 Schema 至少需要一个字段");
        }
        if (!toolMapper.hasActivePromptVersion(tool.getId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工具缺少 ACTIVE Prompt 版本");
        }
    }

    private void validateFieldTypes(List<ToolFieldRequest> fields) {
        for (ToolFieldRequest field : fields) {
            if (!ALLOWED_FIELD_TYPES.contains(field.fieldType())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "不支持的字段类型：" + field.fieldType() + "，仅支持 text/textarea/select/number");
            }
        }
    }

    private ToolSummaryResponse findToolSummary(Long toolId) {
        return toolMapper.findById(toolId)
                .map(ToolSummaryResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
    }

    private AiTool fromRequest(UpsertToolRequest request) {
        AiTool tool = new AiTool();
        tool.setToolCode(request.toolCode());
        tool.setToolName(request.toolName());
        tool.setCategoryId(request.categoryId());
        tool.setDescription(request.description());
        tool.setCoverUrl(request.coverUrl());
        tool.setEstimatedCreditCost(request.estimatedCreditCost());
        return tool;
    }

    private AiTool ensureToolExists(Long toolId) {
        return toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
    }

    private List<ToolFieldResponse> fields(Long toolId) {
        return toolMapper.findActiveFields(toolId).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
    }

    private ToolFieldItem toFieldItem(ToolFieldRequest request) {
        ToolFieldItem item = new ToolFieldItem();
        item.setFieldKey(request.fieldKey());
        item.setFieldName(request.fieldName());
        item.setFieldType(request.fieldType());
        item.setPlaceholder(request.placeholder());
        item.setOptionsJson(request.resolveOptionsJson());
        item.setRequired(request.required() == null || request.required());
        item.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        return item;
    }

    private FieldSchemaResponse toFieldSchemaResponse(ToolFieldSchema schema) {
        List<ToolFieldResponse> fieldList = toolMapper.findFieldsBySchemaId(schema.getId()).stream()
                .map(item -> ToolFieldResponse.from(item, objectMapper))
                .toList();
        return new FieldSchemaResponse(
                schema.getId(),
                schema.getToolId(),
                schema.getSchemaVersion(),
                schema.getStatus(),
                fieldList,
                schema.getCreatedAt(),
                schema.getUpdatedAt()
        );
    }

    private PromptResponse toPromptResponse(ToolPrompt prompt) {
        return new PromptResponse(
                prompt.getId(),
                prompt.getToolId(),
                prompt.getPromptCode(),
                prompt.getPromptName(),
                prompt.getActiveVersionId(),
                prompt.getStatus()
        );
    }

    private PromptVersionResponse toPromptVersionResponse(ToolPromptVersion version) {
        return new PromptVersionResponse(
                version.getId(),
                version.getPromptId(),
                version.getVersionNo(),
                version.getSystemPrompt(),
                version.getUserPromptTemplate(),
                version.getOutputFormat(),
                version.getStatus(),
                version.getCreatedAt(),
                version.getPublishedAt()
        );
    }

    private String renderTemplate(String template, java.util.Map<String, Object> params) {
        if (template == null) {
            return "";
        }
        String output = template;
        if (params != null) {
            for (java.util.Map.Entry<String, Object> entry : params.entrySet()) {
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                output = output.replace("{{" + entry.getKey() + "}}", value);
            }
        }
        return "[TEST PREVIEW]\n" + output;
    }
}
