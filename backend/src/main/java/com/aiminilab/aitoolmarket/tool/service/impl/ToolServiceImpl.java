package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.ExecutionHandler;
import com.aiminilab.aitoolmarket.common.enums.ToolModality;
import com.aiminilab.aitoolmarket.common.enums.ToolStatus;
import com.aiminilab.aitoolmarket.common.enums.ToolType;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.dto.CreateFieldSchemaRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptVersionRequest;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaAdminResponse;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaItemRequest;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptVersionResponse;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateRequest;
import com.aiminilab.aitoolmarket.tool.dto.TestGenerateResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCoverUploadResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertFieldSchemaRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolCategoryRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.support.ConfigNoteMergeSupport;
import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;
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
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.tool.dto.ApplyToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolIntegrationView;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationConfig;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationPlugin;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationRegistry;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationResolver;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import com.aiminilab.aitoolmarket.tool.service.ToolTemplateService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ToolServiceImpl implements ToolService {

    private static final Logger log = LoggerFactory.getLogger(ToolServiceImpl.class);
    private static final long MAX_TOOL_COVER_BYTES = 20L * 1024L * 1024L;
    private static final DateTimeFormatter COVER_FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> TOOL_COVER_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "mp4", "webm", "mov", "m4v"
    );

    private final ToolMapper toolMapper;
    private final ToolCategoryMapper toolCategoryMapper;
    private final ToolFieldSchemaMapper toolFieldSchemaMapper;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ToolPromptMapper toolPromptMapper;
    private final ToolPromptVersionMapper toolPromptVersionMapper;
    private final ObjectMapper objectMapper;
    private final ToolTemplateService toolTemplateService;
    private final ModelCapabilityService modelCapabilityService;
    private final TaskCreditEstimateService taskCreditEstimateService;
    private final AppProperties appProperties;
    private final ToolIntegrationResolver toolIntegrationResolver;
    private final ToolIntegrationRegistry toolIntegrationRegistry;

    public ToolServiceImpl(ToolMapper toolMapper, ToolCategoryMapper toolCategoryMapper,
                           ToolFieldSchemaMapper toolFieldSchemaMapper, ToolFieldItemMapper toolFieldItemMapper,
                           ToolPromptMapper toolPromptMapper, ToolPromptVersionMapper toolPromptVersionMapper,
                           ObjectMapper objectMapper, ToolTemplateService toolTemplateService,
                           ModelCapabilityService modelCapabilityService,
                           TaskCreditEstimateService taskCreditEstimateService,
                           AppProperties appProperties,
                           ToolIntegrationResolver toolIntegrationResolver,
                           ToolIntegrationRegistry toolIntegrationRegistry) {
        this.toolMapper = toolMapper;
        this.toolCategoryMapper = toolCategoryMapper;
        this.toolFieldSchemaMapper = toolFieldSchemaMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.toolPromptMapper = toolPromptMapper;
        this.toolPromptVersionMapper = toolPromptVersionMapper;
        this.objectMapper = objectMapper;
        this.toolTemplateService = toolTemplateService;
        this.modelCapabilityService = modelCapabilityService;
        this.taskCreditEstimateService = taskCreditEstimateService;
        this.appProperties = appProperties;
        this.toolIntegrationResolver = toolIntegrationResolver;
        this.toolIntegrationRegistry = toolIntegrationRegistry;
    }

    @Override
    public List<ToolCategoryResponse> categories() {
        return toolCategoryMapper.findActiveCategories().stream()
                .map(ToolCategoryResponse::from)
                .toList();
    }

    @Override
    public List<ToolCategoryResponse> adminCategories() {
        return toolCategoryMapper.findAllCategories().stream()
                .map(ToolCategoryResponse::from)
                .toList();
    }

    @Override
    public ToolCategoryResponse createCategory(UpsertToolCategoryRequest request) {
        ToolCategory category = toCategory(request);
        toolCategoryMapper.insert(category);
        return ToolCategoryResponse.from(category);
    }

    @Override
    public ToolCategoryResponse updateCategory(Long categoryId, UpsertToolCategoryRequest request) {
        ToolCategory category = ensureCategoryExists(categoryId);
        category.setCategoryCode(request.categoryCode());
        category.setCategoryName(request.categoryName());
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setStatus(normalizeCategoryStatus(request.status()));
        toolCategoryMapper.updateById(category);
        return ToolCategoryResponse.from(category);
    }

    @Override
    public ToolCategoryResponse updateCategoryStatus(Long categoryId, String status) {
        ToolCategory category = ensureCategoryExists(categoryId);
        category.setStatus(normalizeCategoryStatus(status));
        toolCategoryMapper.updateById(category);
        return ToolCategoryResponse.from(category);
    }

    @Override
    public PageResponse<ToolSummaryResponse> userTools(String keyword, Long categoryId, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<ToolSummaryResponse> list = toolMapper
                .findTools(true, keyword, categoryId, null, normalizedPageSize, offset)
                .stream()
                .map(this::toUserFacingSummary)
                .toList();
        long total = toolMapper.countTools(true, keyword, categoryId, null);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    public ToolDetailResponse userToolDetail(String toolCode) {
        AiTool tool = toolMapper.findOnlineByCode(toolCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或未上线"));
        ToolSummaryResponse summary = toUserFacingSummary(tool);
        return ToolDetailResponse.of(summary, fields(tool.getId()), resolveIntegrationView(tool));
    }

    private ToolSummaryResponse toUserFacingSummary(AiTool tool) {
        return ToolSummaryResponse.from(tool, taskCreditEstimateService.estimateUserFacingTaskCredits(tool));
    }

    private ToolIntegrationView resolveIntegrationView(AiTool tool) {
        ToolIntegrationConfig config = toolIntegrationResolver.resolve(tool);
        if (config == null || config.isStandardTask()) {
            return ToolIntegrationView.of(config, null);
        }
        Object extension = toolIntegrationRegistry.find(config.getIntegrationMode())
                .map(plugin -> plugin.userDetailExtension(tool, config))
                .orElse(null);
        return ToolIntegrationView.of(config, extension);
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
    @Transactional
    public ToolSummaryResponse createTool(UpsertToolRequest request, Long operatorId) {
        String toolCode = normalizeToolCode(request.toolCode(), request.toolName());
        if (toolMapper.existsByCode(toolCode)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工具编码已存在");
        }

        AiTool tool = fromRequest(request);
        tool.setToolCode(toolCode);
        if (tool.getExecutionHandler() == null || tool.getExecutionHandler().isBlank()) {
            tool.setExecutionHandler(ExecutionHandler.fromNullable(tool.getToolType()).name());
        }
        Long toolId = toolMapper.insertTool(tool, operatorId);
        toolFieldSchemaMapper.createActiveDefaultSchema(toolId, operatorId);
        if (request.templateCode() != null && !request.templateCode().isBlank()) {
            toolTemplateService.applyToTool(toolId,
                    new ApplyToolTemplateRequest(request.templateCode(), true, true),
                    operatorId);
        } else {
            Long schemaId = toolFieldSchemaMapper.findActiveSchemaId(toolId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具字段配置不存在"));
            toolFieldItemMapper.createDefaultFields(schemaId);
        }
        AiTool persisted = toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        modelCapabilityService.validateToolModelBinding(persisted);
        log.info("Admin created AI tool: toolId={}, toolCode={}, toolType={}, modelConfigId={}, operatorId={}",
                toolId, toolCode, persisted.getToolType(), persisted.getModelConfigId(), operatorId);
        return findToolSummary(toolId);
    }

    @Override
    public ToolCoverUploadResponse uploadToolCover(MultipartFile file, String toolName, String toolCode, String modelName) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要上传的工具展示素材");
        }
        if (file.getSize() > MAX_TOOL_COVER_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工具展示素材不能超过 20MB");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = resolveToolCoverExtension(originalFilename, file.getContentType());
        if (!TOOL_COVER_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持 JPG、PNG、WebP、GIF、MP4、WebM、MOV 展示素材");
        }

        String baseName = String.join("-",
                safeFilenamePart(toolName, "tool"),
                safeFilenamePart(toolCode, "code"),
                safeFilenamePart(modelName, "model")
        ).replaceAll("-{2,}", "-");
        String filename = baseName + "-" + LocalDateTime.now().format(COVER_FILENAME_TIME) + "." + extension;
        Path dir = Path.of(appProperties.getGeneratedMediaDir()).resolve("tool-covers").normalize().toAbsolutePath();
        Path target = dir.resolve(filename).normalize();
        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException ex) {
            log.warn("Failed to store tool cover upload: filename={}, contentType={}, size={}",
                    originalFilename, file.getContentType(), file.getSize(), ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "工具展示素材保存失败，请查看后端日志");
        }

        String url = "/generated/tool-covers/" + filename;
        log.info("Admin uploaded tool cover: url={}, originalFilename={}, contentType={}, size={}",
                url, originalFilename, file.getContentType(), file.getSize());
        return new ToolCoverUploadResponse(url, filename, defaultString(file.getContentType()), file.getSize());
    }

    @Override
    @Transactional
    public void applyTemplate(Long toolId, ApplyToolTemplateRequest request, Long operatorId) {
        ensureToolExists(toolId);
        toolTemplateService.applyToTool(toolId, request, operatorId);
        AiTool persisted = toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        modelCapabilityService.validateToolModelBinding(persisted);
    }

    @Override
    public ToolSummaryResponse updateTool(Long toolId, UpsertToolRequest request, Long operatorId) {
        ensureToolExists(toolId);
        AiTool tool = fromRequest(request);
        tool.setId(toolId);
        AiTool existing = toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        if (tool.getExecutionHandler() == null || tool.getExecutionHandler().isBlank()) {
            tool.setExecutionHandler(existing.getExecutionHandler());
        }
        tool.setConfigNote(ConfigNoteMergeSupport.mergePreservingIntegrationMarkers(
                existing.getConfigNote(), tool.getConfigNote()));
        modelCapabilityService.validateToolModelBinding(tool);
        toolMapper.updateTool(toolId, tool, operatorId);
        return findToolSummary(toolId);
    }

    @Override
    public void deleteTool(Long toolId, Long operatorId) {
        AiTool existing = toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或已删除"));
        int updated = toolMapper.softDeleteTool(toolId, operatorId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在或已删除");
        }
        log.info("Admin deleted AI tool: toolId={}, toolCode={}, toolName={}, operatorId={}",
                toolId, existing.getToolCode(), existing.getToolName(), operatorId);
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
        validateSingleCoreField(request.fields());
        Long schemaId = toolFieldSchemaMapper.findActiveSchemaId(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具字段配置不存在"));
        toolFieldItemMapper.replaceActiveFields(schemaId, request.fields().stream()
                .map(this::toFieldItem)
                .toList());
        return fields(toolId);
    }

    @Override
    public List<FieldSchemaAdminResponse> adminFieldSchemas(Long toolId) {
        return fieldSchemas(toolId).stream()
                .map(this::toFieldSchemaAdminResponse)
                .toList();
    }

    @Override
    public FieldSchemaAdminResponse upsertActiveFieldSchema(Long toolId, UpsertFieldSchemaRequest request, Long operatorId) {
        Long schemaId = toolFieldSchemaMapper.findActiveSchemaId(toolId).orElse(null);
        CreateFieldSchemaRequest createRequest = new CreateFieldSchemaRequest(
                request.schemaVersion(),
                request.items().stream().map(this::toToolFieldRequest).toList()
        );
        FieldSchemaResponse response;
        if (schemaId == null) {
            response = createFieldSchema(toolId, createRequest, operatorId);
        } else {
            validateSingleCoreField(createRequest.fields());
            ToolFieldSchema schema = toolFieldSchemaMapper.selectById(schemaId);
            schema.setSchemaVersion(request.schemaVersion());
            toolFieldSchemaMapper.updateById(schema);
            toolFieldItemMapper.replaceActiveFields(schemaId, createRequest.fields().stream()
                    .map(this::toFieldItem)
                    .toList());
            response = toFieldSchemaResponse(schema);
        }
        return toFieldSchemaAdminResponse(response);
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
        validateSingleCoreField(request.fields());
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
        ToolType toolType = ToolType.fromNullable(request.toolType());
        tool.setToolType(toolType.name());
        tool.setInputModality(normalizeInputModality(toolType, request.inputModality()).name());
        tool.setOutputModality(normalizeOutputModality(toolType, request.outputModality()).name());
        tool.setConfigNote(blankToNull(request.configNote()));
        tool.setEstimatedCreditCost(request.estimatedCreditCost());
        tool.setModelConfigId(request.modelConfigId());
        if (request.executionHandler() != null && !request.executionHandler().isBlank()) {
            tool.setExecutionHandler(ExecutionHandler.fromNullable(request.executionHandler()).name());
        }
        return tool;
    }

    private String normalizeToolCode(String requestedCode, String toolName) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            return requestedCode.trim();
        }

        String baseCode = toolName == null ? "" : toolName.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (baseCode.isBlank()) {
            baseCode = "tool";
        }

        String candidate = baseCode;
        int suffix = 1;
        while (toolMapper.existsByCode(candidate)) {
            candidate = baseCode + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String resolveToolCoverExtension(String originalFilename, String contentType) {
        String filename = originalFilename == null ? "" : originalFilename;
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            String ext = filename.substring(dot + 1).trim().toLowerCase();
            if ("jpeg".equals(ext)) {
                return "jpg";
            }
            if (!ext.isBlank()) {
                return ext;
            }
        }
        String type = contentType == null ? "" : contentType.toLowerCase();
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            case "video/x-m4v" -> "m4v";
            default -> "";
        };
    }

    private String safeFilenamePart(String value, String fallback) {
        String normalized = Normalizer.normalize(defaultString(value), Normalizer.Form.NFKC)
                .trim()
                .replaceAll("[\\\\/:*?\"<>|]+", "-")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.length() > 48 ? normalized.substring(0, 48).replaceAll("-+$", "") : normalized;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private ToolModality normalizeInputModality(ToolType toolType, String value) {
        return ToolModality.fromNullable(value, defaultInputModality(toolType));
    }

    private ToolModality normalizeOutputModality(ToolType toolType, String value) {
        return ToolModality.fromNullable(value, defaultOutputModality(toolType));
    }

    private ToolModality defaultInputModality(ToolType toolType) {
        return switch (toolType) {
            case IMAGE_TO_IMAGE, IMAGE_UNDERSTANDING -> ToolModality.IMAGE;
            case SPEECH_TO_TEXT -> ToolModality.AUDIO;
            case VIDEO_GENERATION -> ToolModality.TEXT;
            case AGENT -> ToolModality.MULTIMODAL;
            default -> ToolModality.TEXT;
        };
    }

    private ToolModality defaultOutputModality(ToolType toolType) {
        return switch (toolType) {
            case IMAGE_GENERATION, IMAGE_TO_IMAGE -> ToolModality.IMAGE;
            case TEXT_TO_SPEECH, MUSIC_GENERATION -> ToolModality.AUDIO;
            case VIDEO_GENERATION -> ToolModality.VIDEO;
            case EMBEDDING, RERANK -> ToolModality.JSON;
            default -> ToolModality.TEXT;
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void ensureToolExists(Long toolId) {
        toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
    }

    private ToolCategory ensureCategoryExists(Long categoryId) {
        ToolCategory category = toolCategoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "Category not found");
        }
        return category;
    }

    private ToolCategory toCategory(UpsertToolCategoryRequest request) {
        ToolCategory category = new ToolCategory();
        category.setCategoryCode(request.categoryCode());
        category.setCategoryName(request.categoryName());
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setStatus(normalizeCategoryStatus(request.status()));
        return category;
    }

    private String normalizeCategoryStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ACTIVE";
        }
        return status.trim().toUpperCase();
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

    private FieldSchemaAdminResponse toFieldSchemaAdminResponse(FieldSchemaResponse response) {
        return new FieldSchemaAdminResponse(
                response.id(),
                response.schemaVersion(),
                response.status(),
                response.fields()
        );
    }

    private ToolFieldRequest toToolFieldRequest(FieldSchemaItemRequest item) {
        return new ToolFieldRequest(
                item.fieldKey(),
                item.fieldName(),
                item.fieldType(),
                item.placeholder(),
                null,
                item.optionsJson(),
                item.required(),
                item.required(),
                item.required(),
                null,
                item.required() != null && item.required() ? "ask_user" : "default",
                "LOW",
                item.sortOrder()
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
        item.setOptionsJson(request.resolveOptionsJson());
        item.setRequired(request.required() == null || request.required());
        item.setExecutionRequired(request.executionRequired() == null ? item.getRequired() : request.executionRequired());
        item.setUserRequired(request.userRequired() == null ? item.getRequired() : request.userRequired());
        item.setDefaultValue(blankToNull(request.defaultValue()));
        item.setAgentFillStrategy(normalizeFillStrategy(request.agentFillStrategy(), item.getUserRequired()));
        item.setRiskLevel(normalizeRiskLevel(request.riskLevel()));
        item.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        return item;
    }

    private String normalizeFillStrategy(String value, Boolean userRequired) {
        if (value == null || value.isBlank()) {
            return Boolean.TRUE.equals(userRequired) ? "ask_user" : "default";
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "infer_from_user", "default", "ask_user", "derive", "none" -> normalized;
            default -> Boolean.TRUE.equals(userRequired) ? "ask_user" : "default";
        };
    }

    private String normalizeRiskLevel(String value) {
        if (value == null || value.isBlank()) {
            return "LOW";
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH" -> normalized;
            default -> "LOW";
        };
    }

    private void validateSingleCoreField(List<ToolFieldRequest> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        List<String> coreFields = fields.stream()
                .filter(this::isCoreField)
                .map(field -> field.fieldName() == null || field.fieldName().isBlank() ? field.fieldKey() : field.fieldName())
                .toList();
        if (coreFields.size() > 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "核心字段只能选择一个：" + String.join("、", coreFields));
        }
    }

    private boolean isCoreField(ToolFieldRequest field) {
        String raw = field == null ? null : field.optionsJson();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return node != null && node.isObject()
                    && (node.path("core").asBoolean(false) || node.path("isCore").asBoolean(false));
        } catch (Exception ignored) {
            return false;
        }
    }
}
