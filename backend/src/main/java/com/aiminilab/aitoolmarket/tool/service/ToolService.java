package com.aiminilab.aitoolmarket.tool.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaAdminResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertFieldSchemaRequest;
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

    List<ToolFieldResponse> adminFields(Long toolId);

    List<ToolFieldResponse> updateFields(Long toolId, UpdateToolFieldsRequest request);

    List<FieldSchemaAdminResponse> adminFieldSchemas(Long toolId);

    FieldSchemaAdminResponse upsertActiveFieldSchema(Long toolId, UpsertFieldSchemaRequest request, Long operatorId);

    FieldSchemaAdminResponse publishFieldSchema(Long schemaId, Long operatorId);
}
