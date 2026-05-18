package com.aiminilab.aitoolmarket.tool.service;

import com.aiminilab.aitoolmarket.tool.dto.ApplyToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolTemplateDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolTemplateSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.entity.ToolTemplate;

import java.util.List;

public interface ToolTemplateService {

    List<ToolTemplateSummaryResponse> list(String toolType, String executionHandler, boolean adminView);

    ToolTemplateDetailResponse detailByCode(String templateCode);

    ToolTemplateDetailResponse create(UpsertToolTemplateRequest request);

    ToolTemplateDetailResponse update(Long templateId, UpsertToolTemplateRequest request);

    void delete(Long templateId);

    ToolTemplate requireByCode(String templateCode);

    void applyToTool(Long toolId, ApplyToolTemplateRequest request, Long operatorId);

    void applyToTool(Long toolId, ToolTemplate template, boolean applyMetadata, boolean overwritePrompt, Long operatorId);
}
