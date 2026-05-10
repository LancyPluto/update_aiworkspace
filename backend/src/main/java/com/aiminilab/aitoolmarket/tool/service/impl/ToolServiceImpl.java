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
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldSchemaMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptVersionMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ToolServiceImpl implements ToolService {

    private final ToolMapper toolMapper;
    private final ToolCategoryMapper toolCategoryMapper;
    private final ToolFieldSchemaMapper toolFieldSchemaMapper;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ToolPromptMapper toolPromptMapper;
    private final ToolPromptVersionMapper toolPromptVersionMapper;
    private final ObjectMapper objectMapper;

    public ToolServiceImpl(ToolMapper toolMapper, ToolCategoryMapper toolCategoryMapper,
                           ToolFieldSchemaMapper toolFieldSchemaMapper, ToolFieldItemMapper toolFieldItemMapper,
                           ToolPromptMapper toolPromptMapper, ToolPromptVersionMapper toolPromptVersionMapper,
                           ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.toolCategoryMapper = toolCategoryMapper;
        this.toolFieldSchemaMapper = toolFieldSchemaMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.toolPromptMapper = toolPromptMapper;
        this.toolPromptVersionMapper = toolPromptVersionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ToolCategoryResponse> categories() {
        return toolCategoryMapper.findActiveCategories().stream()
                .map(ToolCategoryResponse::from)
                .toList();
    }

    @Override
    public PageResponse<ToolSummaryResponse> userTools(String keyword, Long categoryId, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<ToolSummaryResponse> list = toolMapper
                .findTools(true, keyword, categoryId, null, normalizedPageSize, offset)
                .stream()
                .map(ToolSummaryResponse::from)
                .toList();
        long total = toolMapper.countTools(true, keyword, categoryId, null);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    public ToolDetailResponse userToolDetail(String toolCode) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
        ToolSummaryResponse summary = ToolSummaryResponse.from(tool);
        return ToolDetailResponse.of(summary, fields(tool.getId()));
    }

    @Override
    public PageResponse<ToolSummaryResponse> adminTools(String keyword, Long categoryId, String status,
                                                        Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<ToolSummaryResponse> list = toolMapper
                .findTools(false, keyword, categoryId, status, normalizedPageSize, offset)
                .stream()
                .map(ToolSummaryResponse::from)
                .toList();
        long total = toolMapper.countTools(false, keyword, categoryId, status);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    public ToolDetailResponse adminToolDetail(Long toolId) {
        ToolSummaryResponse summary = findToolSummary(toolId);
        return ToolDetailResponse.of(summary, fields(toolId));
    }

    @Override
    public ToolSummaryResponse createTool(UpsertToolRequest request, Long operatorId) {
        if (toolMapper.existsByCode(request.toolCode())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工具编码已存在");
        }

        AiTool tool = fromRequest(request);
        Long toolId = toolMapper.insertTool(tool, operatorId);
        Long schemaId = toolFieldSchemaMapper.createActiveDefaultSchema(toolId, operatorId);
        toolFieldItemMapper.createDefaultFields(schemaId);
        return findToolSummary(toolId);
    }

    @Override
    public ToolSummaryResponse updateTool(Long toolId, UpsertToolRequest request, Long operatorId) {
        ensureToolExists(toolId);
        toolMapper.updateTool(toolId, fromRequest(request), operatorId);
        return findToolSummary(toolId);
    }

    @Override
    public ToolSummaryResponse publishTool(Long toolId, Long operatorId) {
        ensureToolExists(toolId);
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
    public List<ToolFieldResponse> updateFields(Long toolId, UpdateToolFieldsRequest request) {
        ensureToolExists(toolId);
        Long schemaId = toolFieldSchemaMapper.findActiveSchemaId(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具字段配置不存在"));
        toolFieldItemMapper.replaceActiveFields(schemaId, request.fields().stream()
                .map(this::toFieldItem)
                .toList());
        return fields(toolId);
    }

    @Override
    public List<FieldSchemaResponse> fieldSchemas(Long toolId) {
        ensureToolExists(toolId);
        return toolFieldSchemaMapper.selectList(new LambdaQueryWrapper<ToolFieldSchema>()
                        .eq(ToolFieldSchema::getToolId, toolId)
                        .orderByDesc(ToolFieldSchema::getId))
                .stream()
                .map(this::toFieldSchemaResponse)
                .toList();
    }

    @Override
    public FieldSchemaResponse createFieldSchema(Long toolId, CreateFieldSchemaRequest request, Long operatorId) {
        ensureToolExists(toolId);
        ToolFieldSchema schema = new ToolFieldSchema();
        schema.setToolId(toolId);
        schema.setSchemaVersion(request.schemaVersion());
        schema.setStatus("DRAFT");
        schema.setCreatedBy(operatorId);
        toolFieldSchemaMapper.insert(schema);
        toolFieldItemMapper.replaceActiveFields(schema.getId(), request.fields().stream()
                .map(this::toFieldItem)
                .toList());
        return toFieldSchemaResponse(schema);
    }

    @Override
    public FieldSchemaResponse publishFieldSchema(Long schemaId) {
        ToolFieldSchema schema = toolFieldSchemaMapper.selectById(schemaId);
        if (schema == null) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "Tool field schema not found");
        }
        schema.setStatus("ACTIVE");
        toolFieldSchemaMapper.updateById(schema);
        return toFieldSchemaResponse(schema);
    }

    @Override
    public List<PromptResponse> prompts(Long toolId) {
        ensureToolExists(toolId);
        return toolPromptMapper.findByToolId(toolId).stream()
                .map(this::toPromptResponse)
                .toList();
    }

    @Override
    public PromptResponse createPrompt(Long toolId, CreatePromptRequest request) {
        ensureToolExists(toolId);
        ToolPrompt prompt = new ToolPrompt();
        prompt.setToolId(toolId);
        prompt.setPromptCode(request.promptCode());
        prompt.setPromptName(request.promptName());
        prompt.setStatus("ACTIVE");
        toolPromptMapper.insert(prompt);
        return toPromptResponse(prompt);
    }

    @Override
    public List<PromptVersionResponse> promptVersions(Long promptId) {
        ensurePromptExists(promptId);
        return toolPromptVersionMapper.findByPromptId(promptId).stream()
                .map(this::toPromptVersionResponse)
                .toList();
    }

    @Override
    public PromptVersionResponse createPromptVersion(Long promptId, CreatePromptVersionRequest request, Long operatorId) {
        ensurePromptExists(promptId);
        ToolPromptVersion version = new ToolPromptVersion();
        version.setPromptId(promptId);
        version.setVersionNo(request.versionNo());
        version.setSystemPrompt(request.systemPrompt());
        version.setUserPromptTemplate(request.userPromptTemplate());
        version.setOutputFormat(request.outputFormat() == null || request.outputFormat().isBlank()
                ? "MARKDOWN"
                : request.outputFormat());
        version.setStatus("DRAFT");
        toolPromptVersionMapper.insert(version);
        return toPromptVersionResponse(version);
    }

    @Override
    public TestGenerateResponse testGenerate(Long promptVersionId, TestGenerateRequest request) {
        ToolPromptVersion version = findPromptVersion(promptVersionId);
        return new TestGenerateResponse(renderPrompt(version.getUserPromptTemplate(), request.params()));
    }

    @Override
    @Transactional
    public PromptVersionResponse publishPromptVersion(Long promptVersionId) {
        ToolPromptVersion version = findPromptVersion(promptVersionId);
        version.setStatus("ACTIVE");
        version.setPublishedAt(java.time.LocalDateTime.now());
        toolPromptVersionMapper.updateById(version);
        toolPromptVersionMapper.deactivateOtherVersions(version.getPromptId(), version.getId());
        toolPromptVersionMapper.updatePromptActiveVersion(version.getPromptId(), version.getId());
        return toPromptVersionResponse(version);
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

    private void ensureToolExists(Long toolId) {
        toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
    }

    private ToolPrompt ensurePromptExists(Long promptId) {
        return toolPromptMapper.findById(promptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "Prompt not found"));
    }

    private ToolPromptVersion findPromptVersion(Long promptVersionId) {
        return toolPromptVersionMapper.findById(promptVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "Prompt version not found"));
    }

    private List<ToolFieldResponse> fields(Long toolId) {
        return toolFieldItemMapper.findActiveFields(toolId).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
    }

    private FieldSchemaResponse toFieldSchemaResponse(ToolFieldSchema schema) {
        return new FieldSchemaResponse(
                schema.getId(),
                schema.getToolId(),
                schema.getSchemaVersion(),
                schema.getStatus(),
                toolFieldItemMapper.findBySchemaId(schema.getId()).stream()
                        .map(field -> ToolFieldResponse.from(field, objectMapper))
                        .toList(),
                null,
                null
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

    private String renderPrompt(String template, Map<String, Object> params) {
        String output = template == null ? "" : template;
        if (params == null || params.isEmpty()) {
            return output;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            output = output.replace("{{" + entry.getKey() + "}}", value);
        }
        return output;
    }

    private ToolFieldItem toFieldItem(ToolFieldRequest request) {
        ToolFieldItem item = new ToolFieldItem();
        item.setFieldKey(request.fieldKey());
        item.setFieldName(request.fieldName());
        item.setFieldType(request.fieldType());
        item.setPlaceholder(request.placeholder());
        item.setOptionsJson(request.options() == null ? null : request.options().toString());
        item.setRequired(request.required() == null || request.required());
        item.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        return item;
    }
}
