package com.aiminilab.aitoolmarket.tool.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;

import java.util.List;

public interface ToolService {

    List<ToolCategoryResponse> categories();

    PageResponse<ToolSummaryResponse> userTools();

    ToolDetailResponse userToolDetail(String toolCode);

    PageResponse<ToolSummaryResponse> adminTools();

    ToolSummaryResponse createTool(UpsertToolRequest request, Long operatorId);

    ToolSummaryResponse updateTool(Long toolId, UpsertToolRequest request, Long operatorId);

    ToolSummaryResponse publishTool(Long toolId, Long operatorId);

    ToolSummaryResponse offlineTool(Long toolId, Long operatorId);
}
