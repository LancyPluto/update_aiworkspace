package com.aiminilab.aitoolmarket.tool.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;

import java.util.List;

public interface ToolService {

    List<ToolCategoryResponse> categories();

    PageResponse<ToolSummaryResponse> userTools(String keyword, Long categoryId, Integer pageNo, Integer pageSize);

    ToolDetailResponse userToolDetail(String toolCode);

    PageResponse<ToolSummaryResponse> adminTools(String keyword, Long categoryId, String status, Integer pageNo, Integer pageSize);

    ToolDetailResponse adminToolDetail(Long toolId);

    ToolSummaryResponse createTool(UpsertToolRequest request, Long operatorId);

    ToolSummaryResponse updateTool(Long toolId, UpsertToolRequest request, Long operatorId);

    ToolSummaryResponse publishTool(Long toolId, Long operatorId);

    ToolSummaryResponse offlineTool(Long toolId, Long operatorId);

    List<ToolFieldResponse> adminFields(Long toolId);

    List<ToolFieldResponse> updateFields(Long toolId, UpdateToolFieldsRequest request);
}
