package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.ExecutionHandler;
import com.aiminilab.aitoolmarket.common.enums.ToolModality;
import com.aiminilab.aitoolmarket.common.enums.ToolType;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.dto.ApplyToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolTemplateDetailResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolTemplateSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.aiminilab.aitoolmarket.tool.entity.ToolPrompt;
import com.aiminilab.aitoolmarket.tool.entity.ToolPromptVersion;
import com.aiminilab.aitoolmarket.tool.entity.ToolTemplate;
import com.aiminilab.aitoolmarket.tool.entity.ToolTemplateField;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldSchemaMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptVersionMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolTemplateFieldMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolTemplateMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ToolTemplateServiceImpl implements ToolTemplateService {

    private final ToolTemplateMapper toolTemplateMapper;
    private final ToolTemplateFieldMapper toolTemplateFieldMapper;
    private final ToolMapper toolMapper;
    private final ToolFieldSchemaMapper toolFieldSchemaMapper;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ToolPromptMapper toolPromptMapper;
    private final ToolPromptVersionMapper toolPromptVersionMapper;
    private final ObjectMapper objectMapper;

    public ToolTemplateServiceImpl(ToolTemplateMapper toolTemplateMapper,
                                   ToolTemplateFieldMapper toolTemplateFieldMapper,
                                   ToolMapper toolMapper,
                                   ToolFieldSchemaMapper toolFieldSchemaMapper,
                                   ToolFieldItemMapper toolFieldItemMapper,
                                   ToolPromptMapper toolPromptMapper,
                                   ToolPromptVersionMapper toolPromptVersionMapper,
                                   ObjectMapper objectMapper) {
        this.toolTemplateMapper = toolTemplateMapper;
        this.toolTemplateFieldMapper = toolTemplateFieldMapper;
        this.toolMapper = toolMapper;
        this.toolFieldSchemaMapper = toolFieldSchemaMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.toolPromptMapper = toolPromptMapper;
        this.toolPromptVersionMapper = toolPromptVersionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ToolTemplateSummaryResponse> list(String toolType, String executionHandler, boolean adminView) {
        List<ToolTemplate> templates = adminView
                ? toolTemplateMapper.findAllOrdered()
                : toolTemplateMapper.findActive(toolType, executionHandler);
        return templates.stream().map(ToolTemplateSummaryResponse::from).toList();
    }

    @Override
    public ToolTemplateDetailResponse detailByCode(String templateCode) {
        ToolTemplate template = requireByCode(templateCode);
        return toDetail(template);
    }

    @Override
    @Transactional
    public ToolTemplateDetailResponse create(UpsertToolTemplateRequest request) {
        if (toolTemplateMapper.findByCode(request.templateCode()).isPresent()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模板编码已存在");
        }
        ToolTemplate template = toEntity(request, new ToolTemplate());
        template.setSystemTemplate(false);
        toolTemplateMapper.insert(template);
        replaceFields(template.getId(), request.fields());
        return toDetail(template);
    }

    @Override
    @Transactional
    public ToolTemplateDetailResponse update(Long templateId, UpsertToolTemplateRequest request) {
        ToolTemplate template = toolTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具模板不存在");
        }
        if (!request.templateCode().equals(template.getTemplateCode())
                && toolTemplateMapper.findByCode(request.templateCode()).isPresent()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模板编码已存在");
        }
        toEntity(request, template);
        toolTemplateMapper.updateById(template);
        replaceFields(template.getId(), request.fields());
        return toDetail(template);
    }

    @Override
    @Transactional
    public void delete(Long templateId) {
        ToolTemplate template = toolTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具模板不存在");
        }
        if (Boolean.TRUE.equals(template.getSystemTemplate())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "系统内置模板不可删除");
        }
        toolTemplateFieldMapper.deleteByTemplateId(templateId);
        toolTemplateMapper.deleteById(templateId);
    }

    @Override
    public ToolTemplate requireByCode(String templateCode) {
        return toolTemplateMapper.findByCode(templateCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具模板不存在"));
    }

    @Override
    @Transactional
    public void applyToTool(Long toolId, ApplyToolTemplateRequest request, Long operatorId) {
        ToolTemplate template = requireByCode(request.templateCode());
        applyToTool(toolId, template, request.shouldApplyMetadata(), request.shouldOverwritePrompt(), operatorId);
    }

    @Override
    @Transactional
    public void applyToTool(Long toolId, ToolTemplate template, boolean applyMetadata, boolean overwritePrompt, Long operatorId) {
        AiTool tool = toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        tool.setTemplateId(template.getId());
        tool.setExecutionHandler(template.getExecutionHandler());
        if (applyMetadata) {
            tool.setToolType(template.getToolType());
            tool.setInputModality(template.getInputModality());
            tool.setOutputModality(template.getOutputModality());
            tool.setConfigNote(template.getConfigNote());
            if (tool.getModelConfigId() == null && template.getSuggestedModelConfigId() != null) {
                tool.setModelConfigId(template.getSuggestedModelConfigId());
            }
        }
        tool.setUpdatedBy(operatorId);
        toolMapper.updateById(tool);

        Long schemaId = toolFieldSchemaMapper.findActiveSchemaId(toolId)
                .orElseGet(() -> toolFieldSchemaMapper.createActiveDefaultSchema(toolId, operatorId));
        List<ToolFieldItem> items = toolTemplateFieldMapper.findByTemplateId(template.getId()).stream()
                .map(this::toFieldItem)
                .toList();
        toolFieldItemMapper.replaceActiveFields(schemaId, items);

        if (hasPromptTemplate(template)) {
            applyPromptTemplate(toolId, template, overwritePrompt, operatorId);
        }
    }

    private void applyPromptTemplate(Long toolId, ToolTemplate template, boolean overwritePrompt, Long operatorId) {
        ToolPrompt prompt = toolPromptMapper.findByToolIdAndCode(toolId, "default").orElseGet(() -> {
            ToolPrompt created = new ToolPrompt();
            created.setToolId(toolId);
            created.setPromptCode("default");
            created.setPromptName("默认 Prompt");
            created.setStatus("ACTIVE");
            toolPromptMapper.insert(created);
            return created;
        });

        if (!overwritePrompt && prompt.getActiveVersionId() != null) {
            return;
        }

        String versionNo = "tpl-" + System.currentTimeMillis();
        ToolPromptVersion version = new ToolPromptVersion();
        version.setPromptId(prompt.getId());
        version.setVersionNo(versionNo);
        version.setSystemPrompt(template.getDefaultSystemPrompt());
        version.setUserPromptTemplate(template.getDefaultUserPromptTemplate());
        version.setOutputFormat(template.getDefaultOutputFormat() == null || template.getDefaultOutputFormat().isBlank()
                ? "MARKDOWN"
                : template.getDefaultOutputFormat());
        version.setStatus("ACTIVE");
        version.setPublishedAt(java.time.LocalDateTime.now());
        toolPromptVersionMapper.insert(version);
        toolPromptVersionMapper.deactivateOtherVersions(prompt.getId(), version.getId());
        toolPromptVersionMapper.updatePromptActiveVersion(prompt.getId(), version.getId());
    }

    private boolean hasPromptTemplate(ToolTemplate template) {
        return (template.getDefaultUserPromptTemplate() != null && !template.getDefaultUserPromptTemplate().isBlank())
                || (template.getDefaultSystemPrompt() != null && !template.getDefaultSystemPrompt().isBlank());
    }

    private ToolTemplateDetailResponse toDetail(ToolTemplate template) {
        List<ToolFieldResponse> fields = toolTemplateFieldMapper.findByTemplateId(template.getId()).stream()
                .map(field -> ToolFieldResponse.fromTemplateField(field, objectMapper))
                .toList();
        return ToolTemplateDetailResponse.of(template, fields);
    }

    private void replaceFields(Long templateId, List<ToolFieldRequest> fields) {
        toolTemplateFieldMapper.deleteByTemplateId(templateId);
        for (ToolFieldRequest request : fields) {
            ToolTemplateField field = new ToolTemplateField();
            field.setTemplateId(templateId);
            field.setFieldKey(request.fieldKey());
            field.setFieldName(request.fieldName());
            field.setFieldType(request.fieldType());
            field.setPlaceholder(request.placeholder());
            field.setOptionsJson(request.resolveOptionsJson());
            field.setRequired(request.required() == null || request.required());
            field.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
            field.setStatus("ACTIVE");
            toolTemplateFieldMapper.insert(field);
        }
    }

    private ToolTemplate toEntity(UpsertToolTemplateRequest request, ToolTemplate template) {
        template.setTemplateCode(request.templateCode().trim());
        template.setTemplateName(request.templateName().trim());
        template.setToolType(ToolType.fromNullable(request.toolType()).name());
        template.setExecutionHandler(ExecutionHandler.fromNullable(request.executionHandler()).name());
        ToolType toolType = ToolType.fromNullable(request.toolType());
        template.setInputModality(ToolModality.fromNullable(request.inputModality(), defaultInput(toolType)).name());
        template.setOutputModality(ToolModality.fromNullable(request.outputModality(), defaultOutput(toolType)).name());
        template.setConfigNote(blankToNull(request.configNote()));
        template.setDefaultSystemPrompt(request.defaultSystemPrompt());
        template.setDefaultUserPromptTemplate(request.defaultUserPromptTemplate());
        template.setDefaultOutputFormat(request.defaultOutputFormat() == null || request.defaultOutputFormat().isBlank()
                ? "MARKDOWN"
                : request.defaultOutputFormat());
        template.setHandlerConfigJson(blankToNull(request.handlerConfigJson()));
        template.setSuggestedModelConfigId(request.suggestedModelConfigId());
        template.setStatus(request.status() == null || request.status().isBlank() ? "ACTIVE" : request.status().trim().toUpperCase());
        template.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        return template;
    }

    private ToolModality defaultInput(ToolType toolType) {
        return switch (toolType) {
            case IMAGE_TO_IMAGE, IMAGE_UNDERSTANDING -> ToolModality.IMAGE;
            case SPEECH_TO_TEXT -> ToolModality.AUDIO;
            case AGENT -> ToolModality.MULTIMODAL;
            default -> ToolModality.TEXT;
        };
    }

    private ToolModality defaultOutput(ToolType toolType) {
        return switch (toolType) {
            case IMAGE_GENERATION, IMAGE_TO_IMAGE -> ToolModality.IMAGE;
            case TEXT_TO_SPEECH, MUSIC_GENERATION -> ToolModality.AUDIO;
            case VIDEO_GENERATION -> ToolModality.VIDEO;
            case EMBEDDING, RERANK -> ToolModality.JSON;
            default -> ToolModality.TEXT;
        };
    }

    private ToolFieldItem toFieldItem(ToolTemplateField field) {
        ToolFieldItem item = new ToolFieldItem();
        item.setFieldKey(field.getFieldKey());
        item.setFieldName(field.getFieldName());
        item.setFieldType(field.getFieldType());
        item.setPlaceholder(field.getPlaceholder());
        item.setOptionsJson(field.getOptionsJson());
        item.setRequired(field.getRequired() != null && field.getRequired());
        item.setSortOrder(field.getSortOrder() == null ? 0 : field.getSortOrder());
        return item;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
