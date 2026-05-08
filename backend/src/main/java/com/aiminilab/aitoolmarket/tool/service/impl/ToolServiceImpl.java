package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.ToolStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
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
        List<ToolFieldResponse> fields = toolMapper.findActiveFields(tool.getId()).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
        return ToolDetailResponse.of(summary, fields);
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
        return toolMapper.findById(toolId)
                .map(ToolSummaryResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
    }

    @Override
    public ToolSummaryResponse updateTool(Long toolId, UpsertToolRequest request, Long operatorId) {
        ensureToolExists(toolId);
        toolMapper.updateTool(toolId, fromRequest(request), operatorId);
        return toolMapper.findById(toolId)
                .map(ToolSummaryResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
    }

    @Override
    public ToolSummaryResponse publishTool(Long toolId, Long operatorId) {
        ensureToolExists(toolId);
        toolMapper.updateToolStatus(toolId, ToolStatus.ONLINE, operatorId);
        return toolMapper.findById(toolId)
                .map(ToolSummaryResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
    }

    @Override
    public ToolSummaryResponse offlineTool(Long toolId, Long operatorId) {
        ensureToolExists(toolId);
        toolMapper.updateToolStatus(toolId, ToolStatus.OFFLINE, operatorId);
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
}
