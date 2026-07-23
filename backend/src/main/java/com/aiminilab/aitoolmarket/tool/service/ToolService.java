package com.aiminilab.aitoolmarket.tool.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaAdminResponse;
import com.aiminilab.aitoolmarket.tool.dto.PublicToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.PublicToolView;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCoverUploadResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.CreateFieldSchemaRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptVersionRequest;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptVersionResponse;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateRequest;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertFieldSchemaRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolCategoryRequest;
import com.aiminilab.aitoolmarket.tool.dto.ApplyToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ToolService {

    List<ToolCategoryResponse> categories();

    List<ToolCategoryResponse> adminCategories();

    ToolCategoryResponse createCategory(UpsertToolCategoryRequest request);

    ToolCategoryResponse updateCategory(Long categoryId, UpsertToolCategoryRequest request);

    ToolCategoryResponse updateCategoryStatus(Long categoryId, String status);

    PageResponse<?> userTools(
            String keyword,
            Long categoryId,
            Integer pageNo,
            Integer pageSize,
            PublicToolView view
    );

    PublicToolDetailResponse userToolDetail(String toolCode);

    PageResponse<ToolSummaryResponse> adminTools(String keyword, Long categoryId, String status, Integer pageNo, Integer pageSize);

    ToolDetailResponse adminToolDetail(Long toolId);

    ToolSummaryResponse createTool(UpsertToolRequest request, Long operatorId);

    ToolCoverUploadResponse uploadToolCover(MultipartFile file, String toolName, String toolCode, String modelName);

    void applyTemplate(Long toolId, ApplyToolTemplateRequest request, Long operatorId);

    ToolSummaryResponse updateTool(Long toolId, UpsertToolRequest request, Long operatorId);

    void deleteTool(Long toolId, Long operatorId);

    ToolSummaryResponse publishTool(Long toolId, Long operatorId);

    ToolSummaryResponse offlineTool(Long toolId, Long operatorId);

    List<ToolFieldResponse> adminFields(Long toolId);

    List<ToolFieldResponse> updateFields(Long toolId, UpdateToolFieldsRequest request);

    List<FieldSchemaAdminResponse> adminFieldSchemas(Long toolId);

    FieldSchemaAdminResponse upsertActiveFieldSchema(Long toolId, UpsertFieldSchemaRequest request, Long operatorId);

    List<FieldSchemaResponse> fieldSchemas(Long toolId);

    FieldSchemaResponse createFieldSchema(Long toolId, CreateFieldSchemaRequest request, Long operatorId);

    FieldSchemaResponse publishFieldSchema(Long schemaId);

    List<PromptResponse> prompts(Long toolId);

    PromptResponse createPrompt(Long toolId, CreatePromptRequest request);

    List<PromptVersionResponse> promptVersions(Long promptId);

    PromptVersionResponse createPromptVersion(Long promptId, CreatePromptVersionRequest request, Long operatorId);

    TestGenerateResponse testGenerate(Long promptVersionId, TestGenerateRequest request);

    PromptVersionResponse publishPromptVersion(Long promptVersionId);

    int adminMigrateCovers();
}
