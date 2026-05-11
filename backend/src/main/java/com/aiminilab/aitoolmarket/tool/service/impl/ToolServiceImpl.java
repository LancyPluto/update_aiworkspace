package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.ToolStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaAdminResponse;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaItemRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldSchemaSummary;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertFieldSchemaRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolServiceImpl implements ToolService {

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
    public ToolSummaryResponse createTool(UpsertToolRequest request, Long operatorId) {
        AiTool tool = fromRequest(request);
        Long toolId = toolMapper.insertTool(tool, operatorId);
        Long schemaId = toolMapper.createActiveDefaultSchema(toolId, operatorId);
        toolMapper.createDefaultFields(schemaId);
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
        Long schemaId = toolMapper.findActiveSchemaId(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具字段配置不存在"));
        toolMapper.replaceActiveFields(schemaId, request.fields().stream()
                .map(this::toFieldItem)
                .toList());
        return fields(toolId);
    }

    @Override
    public List<FieldSchemaAdminResponse> adminFieldSchemas(Long toolId) {
        ensureToolExists(toolId);
        return toolMapper.listFieldSchemasByTool(toolId).stream()
                .map(this::toFieldSchemaAdminResponse)
                .toList();
    }

    @Override
    public FieldSchemaAdminResponse upsertActiveFieldSchema(Long toolId, UpsertFieldSchemaRequest request, Long operatorId) {
        ensureToolExists(toolId);
        Long schemaId = toolMapper.findActiveSchemaId(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具字段配置不存在"));
        toolMapper.updateSchemaVersion(schemaId, request.schemaVersion());
        List<ToolFieldRequest> fieldRequests = request.items().stream()
                .map(this::toToolFieldRequestFromContract)
                .toList();
        toolMapper.replaceActiveFields(schemaId, fieldRequests.stream()
                .map(this::toFieldItem)
                .toList());
        return responseForSchema(schemaId);
    }

    @Override
    public FieldSchemaAdminResponse publishFieldSchema(Long schemaId, Long operatorId) {
        toolMapper.findToolIdBySchemaId(schemaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "字段 Schema 不存在"));
        toolMapper.publishSchemaExclusiveActive(schemaId);
        return responseForSchema(schemaId);
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
        item.setOptionsJson(request.options() == null ? null : request.options().toString());
        item.setRequired(request.required() == null || request.required());
        item.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        return item;
    }

    private FieldSchemaAdminResponse toFieldSchemaAdminResponse(ToolFieldSchemaSummary row) {
        return new FieldSchemaAdminResponse(
                row.id(),
                row.schemaVersion(),
                row.status(),
                toolMapper.findFieldsForSchemaId(row.id()).stream()
                        .map(field -> ToolFieldResponse.from(field, objectMapper))
                        .toList()
        );
    }

    private FieldSchemaAdminResponse responseForSchema(Long schemaId) {
        ToolFieldSchemaSummary summary = toolMapper.findSchemaSummary(schemaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "字段 Schema 不存在"));
        return toFieldSchemaAdminResponse(summary);
    }

    private ToolFieldRequest toToolFieldRequestFromContract(FieldSchemaItemRequest item) {
        JsonNode options = null;
        if (item.optionsJson() != null && !item.optionsJson().isBlank()) {
            try {
                options = objectMapper.readTree(item.optionsJson());
            } catch (Exception exception) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "optionsJson 不是合法 JSON");
            }
        }
        return new ToolFieldRequest(
                item.fieldKey(),
                item.fieldName(),
                item.fieldType(),
                item.placeholder(),
                options,
                item.required(),
                item.sortOrder()
        );
    }
}
