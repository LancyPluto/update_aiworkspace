package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleDto;
import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleImportResult;
import com.aiminilab.aitoolmarket.admin.service.ConfigBundleService;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptRequest;
import com.aiminilab.aitoolmarket.tool.dto.CreatePromptVersionRequest;
import com.aiminilab.aitoolmarket.tool.dto.PromptResponse;
import com.aiminilab.aitoolmarket.tool.dto.PromptVersionResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpdateToolFieldsRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolCategoryRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.dto.UpsertWorkflowRequest;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowResponse;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;
import com.aiminilab.aitoolmarket.tool.entity.ToolPromptVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptVersionMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import com.aiminilab.aitoolmarket.tool.service.WorkflowService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConfigBundleServiceImpl implements ConfigBundleService {

    private static final String FORMAT = "ai-tool-market-config-bundle";
    private static final int VERSION = 1;
    private static final Set<String> SECRET_KEY_PARTS = Set.of("secret", "token", "password", "apikey", "api_key", "key");

    private final SystemSettingService systemSettingService;
    private final AgentModelConfigService agentModelConfigService;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ToolService toolService;
    private final WorkflowService workflowService;
    private final ToolMapper toolMapper;
    private final ToolCategoryMapper toolCategoryMapper;
    private final ToolPromptVersionMapper toolPromptVersionMapper;
    private final ModelProviderRegistry modelProviderRegistry;

    public ConfigBundleServiceImpl(SystemSettingService systemSettingService,
                                   AgentModelConfigService agentModelConfigService,
                                   AgentModelConfigMapper agentModelConfigMapper,
                                   ToolService toolService,
                                   WorkflowService workflowService,
                                   ToolMapper toolMapper,
                                   ToolCategoryMapper toolCategoryMapper,
                                   ToolPromptVersionMapper toolPromptVersionMapper,
                                   ModelProviderRegistry modelProviderRegistry) {
        this.systemSettingService = systemSettingService;
        this.agentModelConfigService = agentModelConfigService;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.toolService = toolService;
        this.workflowService = workflowService;
        this.toolMapper = toolMapper;
        this.toolCategoryMapper = toolCategoryMapper;
        this.toolPromptVersionMapper = toolPromptVersionMapper;
        this.modelProviderRegistry = modelProviderRegistry;
    }

    @Override
    public ConfigBundleDto exportBundle(Long operatorId, boolean includeSecrets) {
        Map<Long, String> categoryCodesById = toolService.adminCategories().stream()
                .collect(Collectors.toMap(ToolCategoryResponse::id, ToolCategoryResponse::categoryCode, (a, b) -> a));
        Map<Long, String> modelCodesById = agentModelConfigService.adminList().stream()
                .collect(Collectors.toMap(AgentModelConfigResponse::id, this::stableModelConfigCode, (a, b) -> a));

        List<ToolSummaryResponse> tools = exportAllTools();
        List<ConfigBundleDto.Tool> exportedTools = tools.stream()
                .map(tool -> exportTool(tool, categoryCodesById, modelCodesById))
                .toList();

        return new ConfigBundleDto(
                FORMAT,
                VERSION,
                OffsetDateTime.now().toString(),
                operatorId == null ? null : String.valueOf(operatorId),
                !includeSecrets,
                redactSettings(systemSettingService.settings()),
                agentModelConfigService.adminList().stream()
                        .map(config -> exportModelConfig(config, includeSecrets))
                        .toList(),
                toolService.adminCategories().stream().map(this::exportCategory).toList(),
                exportedTools
        );
    }

    @Override
    @Transactional
    public ConfigBundleImportResult importBundle(ConfigBundleDto bundle, Long operatorId) {
        if (bundle == null || bundle.format() == null || !FORMAT.equals(bundle.format())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported config bundle format");
        }

        List<String> warnings = new ArrayList<>();
        int settings = importSettings(bundle.settings());
        ImportModelResult modelResult = importModelConfigs(safeList(bundle.modelConfigs()), warnings, bundle.secretsRedacted());
        int categories = importCategories(safeList(bundle.categories()));

        Map<String, Long> modelIdsByCode = agentModelConfigService.adminList().stream()
                .filter(config -> stableModelConfigCode(config) != null)
                .collect(Collectors.toMap(this::stableModelConfigCode, AgentModelConfigResponse::id, (a, b) -> a));
        modelIdsByCode.putAll(modelResult.modelIdsByImportedCode);
        Map<String, Long> categoryIdsByCode = toolService.adminCategories().stream()
                .collect(Collectors.toMap(ToolCategoryResponse::categoryCode, ToolCategoryResponse::id, (a, b) -> a));

        ImportCounter counter = new ImportCounter(settings, modelResult.changed, categories);
        for (ConfigBundleDto.Tool tool : safeList(bundle.tools())) {
            importTool(tool, operatorId, modelIdsByCode, categoryIdsByCode, counter, warnings);
        }

        return new ConfigBundleImportResult(
                counter.settings,
                counter.modelConfigs,
                counter.categories,
                counter.tools,
                counter.fields,
                counter.prompts,
                counter.promptVersions,
                counter.workflows,
                warnings
        );
    }

    private ConfigBundleDto.Tool exportTool(ToolSummaryResponse tool,
                                            Map<Long, String> categoryCodesById,
                                            Map<Long, String> modelCodesById) {
        List<ConfigBundleDto.Field> fields = toolService.adminFields(tool.id()).stream()
                .map(this::exportField)
                .toList();
        List<ConfigBundleDto.Prompt> prompts = toolService.prompts(tool.id()).stream()
                .map(this::exportPrompt)
                .toList();
        WorkflowResponse workflow = workflowService.getWorkflow(tool.id());
        return new ConfigBundleDto.Tool(
                tool.toolCode(),
                tool.toolName(),
                categoryCodesById.get(tool.categoryId()),
                tool.description(),
                tool.coverUrl(),
                tool.toolType(),
                tool.inputModality(),
                tool.outputModality(),
                tool.configNote(),
                tool.status(),
                tool.estimatedCreditCost(),
                modelCodesById.get(tool.modelConfigId()),
                fields,
                prompts,
                exportWorkflow(workflow)
        );
    }

    private List<ToolSummaryResponse> exportAllTools() {
        int page = 1;
        int pageSize = 200;
        List<ToolSummaryResponse> result = new ArrayList<>();
        while (true) {
            PageResponse<ToolSummaryResponse> tools = toolService.adminTools(null, null, null, page, pageSize);
            result.addAll(tools.list());
            if (tools.list().size() < pageSize || result.size() >= tools.total()) {
                return result;
            }
            page++;
        }
    }

    private ConfigBundleDto.ModelConfig exportModelConfig(AgentModelConfigResponse config, boolean includeSecrets) {
        AgentModelConfig secretSource = includeSecrets && config.id() != null
                ? agentModelConfigMapper.findActiveById(config.id())
                : null;
        return new ConfigBundleDto.ModelConfig(
                config.displayName(),
                stableModelConfigCode(config),
                config.provider(),
                config.modelName(),
                config.baseUrl(),
                secretSource == null ? "" : nullToEmpty(secretSource.getApiKey()),
                secretSource == null ? "" : nullToEmpty(secretSource.getExtraAuthJson()),
                !includeSecrets,
                config.minimaxGroupId(),
                config.consoleUrl(),
                config.balanceUrl(),
                config.docsUrl(),
                config.timeoutSeconds(),
                config.inputTokenPricePer1k(),
                config.outputTokenPricePer1k(),
                config.inputTokenPricePer1m(),
                config.outputTokenPricePer1m(),
                config.billingUnit(),
                config.unitPrice(),
                config.enabled(),
                config.agentEnabled(),
                config.isDefault(),
                config.capabilities()
        );
    }

    private ConfigBundleDto.Category exportCategory(ToolCategoryResponse category) {
        return new ConfigBundleDto.Category(
                category.categoryCode(),
                category.categoryName(),
                category.sortOrder(),
                category.status()
        );
    }

    private ConfigBundleDto.Field exportField(ToolFieldResponse field) {
        return new ConfigBundleDto.Field(
                field.fieldKey(),
                field.fieldName(),
                field.fieldType(),
                field.placeholder(),
                field.options(),
                field.optionsJson(),
                field.required(),
                field.executionRequired(),
                field.userRequired(),
                field.defaultValue(),
                field.agentFillStrategy(),
                field.riskLevel(),
                field.sortOrder()
        );
    }

    private ConfigBundleDto.Prompt exportPrompt(PromptResponse prompt) {
        List<PromptVersionResponse> versions = toolService.promptVersions(prompt.id());
        String activeVersionNo = versions.stream()
                .filter(version -> Objects.equals(version.id(), prompt.activeVersionId()))
                .map(PromptVersionResponse::versionNo)
                .findFirst()
                .orElse(null);
        return new ConfigBundleDto.Prompt(
                prompt.promptCode(),
                prompt.promptName(),
                prompt.status(),
                activeVersionNo,
                versions.stream().map(this::exportPromptVersion).toList()
        );
    }

    private ConfigBundleDto.PromptVersion exportPromptVersion(PromptVersionResponse version) {
        return new ConfigBundleDto.PromptVersion(
                version.versionNo(),
                version.systemPrompt(),
                version.userPromptTemplate(),
                version.outputFormat(),
                version.status()
        );
    }

    private ConfigBundleDto.Workflow exportWorkflow(WorkflowResponse workflow) {
        if (workflow == null) {
            return null;
        }
        return new ConfigBundleDto.Workflow(
                workflow.workflowName(),
                workflow.nodesJson(),
                workflow.edgesJson(),
                workflow.groupsJson(),
                workflow.configJson(),
                workflow.status()
        );
    }

    private int importSettings(Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) {
            return 0;
        }
        Map<String, String> clean = settings.entrySet().stream()
                .filter(entry -> !looksSensitive(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() == null ? "" : entry.getValue(),
                        (a, b) -> b, LinkedHashMap::new));
        if (clean.isEmpty()) {
            return 0;
        }
        systemSettingService.updateSettings(clean);
        return clean.size();
    }

    private ImportModelResult importModelConfigs(List<ConfigBundleDto.ModelConfig> configs,
                                                 List<String> warnings,
                                                 Boolean bundleSecretsRedacted) {
        int count = 0;
        Map<String, Long> modelIdsByImportedCode = new LinkedHashMap<>();
        for (ConfigBundleDto.ModelConfig config : configs) {
            if (isBlank(config.configCode()) || isBlank(config.provider()) || isBlank(config.modelName())) {
                warnings.add("Skipped model config with missing configCode/provider/modelName");
                continue;
            }
            if (!modelProviderRegistry.isSupported(config.provider())) {
                warnings.add("Skipped model config " + config.configCode()
                        + ": unsupported provider " + config.provider());
                continue;
            }
            boolean secretsRedacted = config.secretsRedacted() != null
                    ? config.secretsRedacted()
                    : bundleSecretsRedacted == null || bundleSecretsRedacted;
            AgentModelConfigRequest request = new AgentModelConfigRequest(
                    config.displayName(),
                    config.configCode(),
                    config.provider(),
                    config.modelName(),
                    config.baseUrl(),
                    secretsRedacted ? "" : nullToEmpty(config.apiKey()),
                    secretsRedacted ? "" : nullToEmpty(config.extraAuthJson()),
                    config.minimaxGroupId(),
                    config.consoleUrl(),
                    config.balanceUrl(),
                    config.docsUrl(),
                    config.timeoutSeconds(),
                    null,
                    null,
                    config.inputTokenPricePer1k(),
                    config.outputTokenPricePer1k(),
                    config.inputTokenPricePer1m(),
                    config.outputTokenPricePer1m(),
                    config.billingUnit(),
                    config.unitPrice(),
                    config.enabled(),
                    config.agentEnabled(),
                    config.isDefault(),
                    config.capabilities()
            );
            AgentModelConfig existing = agentModelConfigMapper.findActiveByConfigCode(config.configCode());
            if (existing == null) {
                Optional<AgentModelConfigResponse> equivalent = findEquivalentModelConfig(config);
                if (equivalent.isPresent()) {
                    modelIdsByImportedCode.put(config.configCode(), equivalent.get().id());
                    warnings.add("Reused existing model config " + stableModelConfigCode(equivalent.get())
                            + " for imported model config " + config.configCode());
                    continue;
                }
                AgentModelConfigResponse created = agentModelConfigService.adminCreate(request);
                modelIdsByImportedCode.put(config.configCode(), created.id());
            } else {
                AgentModelConfigResponse updated = agentModelConfigService.adminUpdate(existing.getId(), request);
                modelIdsByImportedCode.put(config.configCode(), updated.id());
            }
            count++;
        }
        return new ImportModelResult(count, modelIdsByImportedCode);
    }

    private int importCategories(List<ConfigBundleDto.Category> categories) {
        int count = 0;
        for (ConfigBundleDto.Category item : categories) {
            if (isBlank(item.categoryCode()) || isBlank(item.categoryName())) {
                continue;
            }
            ToolCategory existing = findCategoryByCode(item.categoryCode()).orElse(null);
            UpsertToolCategoryRequest request = new UpsertToolCategoryRequest(
                    item.categoryCode(),
                    item.categoryName(),
                    item.sortOrder(),
                    item.status()
            );
            if (existing == null) {
                toolService.createCategory(request);
            } else {
                toolService.updateCategory(existing.getId(), request);
            }
            count++;
        }
        return count;
    }

    private void importTool(ConfigBundleDto.Tool item,
                            Long operatorId,
                            Map<String, Long> modelIdsByCode,
                            Map<String, Long> categoryIdsByCode,
                            ImportCounter counter,
                            List<String> warnings) {
        if (isBlank(item.toolCode()) || isBlank(item.toolName())) {
            warnings.add("Skipped tool with missing toolCode/toolName");
            return;
        }
        Long categoryId = categoryIdsByCode.get(item.categoryCode());
        if (categoryId == null) {
            warnings.add("Skipped tool " + item.toolCode() + ": categoryCode not found: " + item.categoryCode());
            return;
        }
        Long modelConfigId = isBlank(item.modelConfigCode()) ? null : modelIdsByCode.get(item.modelConfigCode());
        if (!isBlank(item.modelConfigCode()) && modelConfigId == null) {
            warnings.add("Tool " + item.toolCode() + " imported without model binding; modelConfigCode not found: " + item.modelConfigCode());
        }

        UpsertToolRequest request = new UpsertToolRequest(
                item.toolCode(),
                item.toolName(),
                categoryId,
                item.description(),
                item.coverUrl(),
                item.toolType(),
                item.inputModality(),
                item.outputModality(),
                item.configNote(),
                item.estimatedCreditCost() == null ? 0 : item.estimatedCreditCost(),
                modelConfigId,
                null
        );
        AiTool existing = findToolByCode(item.toolCode()).orElse(null);
        ToolSummaryResponse saved = existing == null
                ? toolService.createTool(request, operatorId)
                : toolService.updateTool(existing.getId(), request, operatorId);
        counter.tools++;

        if (!safeList(item.fields()).isEmpty()) {
            toolService.updateFields(saved.id(), new UpdateToolFieldsRequest(safeList(item.fields()).stream()
                    .map(field -> new ToolFieldRequest(
                            field.fieldKey(),
                            field.fieldName(),
                            field.fieldType(),
                            field.placeholder(),
                            field.options(),
                            field.optionsJson(),
                            field.required(),
                            field.executionRequired(),
                            field.userRequired(),
                            field.defaultValue(),
                            field.agentFillStrategy(),
                            field.riskLevel(),
                            field.sortOrder()
                    ))
                    .toList()));
            counter.fields += item.fields().size();
        }

        importPrompts(saved.id(), safeList(item.prompts()), counter);
        importWorkflow(saved.id(), item.workflow(), operatorId, counter);

        String status = item.status() == null ? "" : item.status().trim().toUpperCase(Locale.ROOT);
        if ("ONLINE".equals(status)) {
            toolService.publishTool(saved.id(), operatorId);
        } else if ("OFFLINE".equals(status)) {
            toolService.offlineTool(saved.id(), operatorId);
        }
    }

    private void importPrompts(Long toolId, List<ConfigBundleDto.Prompt> prompts, ImportCounter counter) {
        for (ConfigBundleDto.Prompt item : prompts) {
            if (isBlank(item.promptCode()) || isBlank(item.promptName())) {
                continue;
            }
            PromptResponse prompt = toolService.prompts(toolId).stream()
                    .filter(existing -> item.promptCode().equals(existing.promptCode()))
                    .findFirst()
                    .orElseGet(() -> toolService.createPrompt(toolId, new CreatePromptRequest(item.promptCode(), item.promptName())));
            counter.prompts++;

            Map<String, PromptVersionResponse> existingVersions = toolService.promptVersions(prompt.id()).stream()
                    .collect(Collectors.toMap(PromptVersionResponse::versionNo, Function.identity(), (a, b) -> a));
            for (ConfigBundleDto.PromptVersion version : safeList(item.versions())) {
                if (isBlank(version.versionNo()) || isBlank(version.userPromptTemplate())) {
                    continue;
                }
                PromptVersionResponse saved = existingVersions.get(version.versionNo());
                if (saved == null) {
                    saved = toolService.createPromptVersion(prompt.id(), new CreatePromptVersionRequest(
                            version.versionNo(),
                            version.systemPrompt(),
                            version.userPromptTemplate(),
                            version.outputFormat()
                    ), null);
                } else {
                    ToolPromptVersion entity = toolPromptVersionMapper.selectById(saved.id());
                    entity.setSystemPrompt(version.systemPrompt());
                    entity.setUserPromptTemplate(version.userPromptTemplate());
                    entity.setOutputFormat(isBlank(version.outputFormat()) ? "MARKDOWN" : version.outputFormat());
                    toolPromptVersionMapper.updateById(entity);
                }
                if ("ACTIVE".equalsIgnoreCase(version.status()) || version.versionNo().equals(item.activeVersionNo())) {
                    toolService.publishPromptVersion(saved.id());
                }
                counter.promptVersions++;
            }
        }
    }

    private void importWorkflow(Long toolId, ConfigBundleDto.Workflow workflow, Long operatorId, ImportCounter counter) {
        if (workflow == null || isBlank(workflow.workflowName())
                || isBlank(workflow.nodesJson()) || isBlank(workflow.edgesJson())) {
            return;
        }
        workflowService.saveWorkflow(toolId, new UpsertWorkflowRequest(
                workflow.workflowName(),
                workflow.nodesJson(),
                workflow.edgesJson(),
                workflow.groupsJson(),
                workflow.configJson(),
                workflow.status()
        ), operatorId);
        counter.workflows++;
    }

    private Map<String, String> redactSettings(Map<String, String> settings) {
        Map<String, String> result = new LinkedHashMap<>();
        settings.forEach((key, value) -> {
            if (!looksSensitive(key)) {
                result.put(key, value);
            }
        });
        return result;
    }

    private boolean looksSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replace("-", "_").replace(".", "_");
        return SECRET_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private Optional<ToolCategory> findCategoryByCode(String categoryCode) {
        return toolCategoryMapper.selectList(new LambdaQueryWrapper<ToolCategory>()
                        .eq(ToolCategory::getCategoryCode, categoryCode)
                        .last("LIMIT 1"))
                .stream()
                .findFirst();
    }

    private Optional<AiTool> findToolByCode(String toolCode) {
        return toolMapper.selectList(new LambdaQueryWrapper<AiTool>()
                        .eq(AiTool::getToolCode, toolCode)
                        .eq(AiTool::getDeleted, false)
                        .last("LIMIT 1"))
                .stream()
                .findFirst();
    }

    private String stableModelConfigCode(AgentModelConfigResponse config) {
        if (!isBlank(config.configCode())) {
            return config.configCode();
        }
        return "model_" + config.id();
    }

    private Optional<AgentModelConfigResponse> findEquivalentModelConfig(ConfigBundleDto.ModelConfig imported) {
        return agentModelConfigService.adminList().stream()
                .filter(existing -> sameModelIdentity(existing, imported))
                .findFirst();
    }

    private boolean sameModelIdentity(AgentModelConfigResponse existing, ConfigBundleDto.ModelConfig imported) {
        return sameText(existing.provider(), imported.provider())
                && sameText(existing.modelName(), imported.modelName())
                && sameText(existing.baseUrl(), imported.baseUrl());
    }

    private boolean sameText(String left, String right) {
        return normalizeText(left).equals(normalizeText(right));
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static final class ImportCounter {
        private final int settings;
        private final int modelConfigs;
        private final int categories;
        private int tools;
        private int fields;
        private int prompts;
        private int promptVersions;
        private int workflows;

        private ImportCounter(int settings, int modelConfigs, int categories) {
            this.settings = settings;
            this.modelConfigs = modelConfigs;
            this.categories = categories;
        }
    }

    private record ImportModelResult(int changed, Map<String, Long> modelIdsByImportedCode) {
    }
}
