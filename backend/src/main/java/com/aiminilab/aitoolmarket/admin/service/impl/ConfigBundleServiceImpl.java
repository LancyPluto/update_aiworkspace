package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleDto;
import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleImportResult;
import com.aiminilab.aitoolmarket.admin.service.ConfigBundleService;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountRequest;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolDescriptorExtension;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolDescriptorExtensionMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountService;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.entity.PricingRule;
import com.aiminilab.aitoolmarket.credit.mapper.PricingRuleMapper;
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
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldSchemaMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptVersionMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import com.aiminilab.aitoolmarket.tool.service.WorkflowService;
import com.aiminilab.aitoolmarket.tool.support.ConfigNoteMergeSupport;
import com.aiminilab.aitoolmarket.tool.support.ToolFrontendStyleConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final Set<String> EXTRA_AUTH_METADATA_KEYS = Set.of(
            "pricingVerifiedAt",
            "pricingNote",
            "pricingSource",
            "billingDetail",
            "billingNote",
            "balanceNote",
            "costAffectingParams",
            "defaultBillingParams"
    );
    private static final Set<String> LEGACY_KLING_MODEL_CONFIG_CODES = Set.of(
            "kling-v3-omni",
            "kling_image_to_video",
            "kling-v1-image-to-video",
            "kling-v2-master-image-to-video",
            "kling-image-generation-v1-model",
            "kling-image-generation-v3-model",
            "kling-v1-5-image-to-video",
            "kling-v1-6-image-to-video",
            "kling-v2-1-image-to-video",
            "kling-v2-1-master-image-to-video",
            "kling-v2-5-turbo-image-to-video",
            "kling-v2-6-motion-control",
            "kling-v2-6-image-to-video",
            "kling-v3-motion-control",
            "kling-v3-image-to-video",
            "kling-v3-text-to-video",
            "kling-video-o1-omni",
            "8"
    );
    private static final Set<String> LEGACY_KLING_TOOL_CODES = Set.of(
            "v2_1",
            "tool",
            "kling_image_to_video",
            "kling-v3-omni",
            "kling-video-o1-omni",
            "kling-v3-text-to-video",
            "kling-v3-image-to-video",
            "kling-v3-multi-image-reference",
            "kling-v3-motion-control",
            "kling-v2-6-image-to-video",
            "kling-v2-6-motion-control",
            "kling-v2-5-turbo-image-to-video",
            "kling-v2-1-master-image-to-video",
            "kling-v2-1-image-to-video",
            "kling-v2-master-image-to-video",
            "kling-v1-6-image-to-video",
            "kling-v1-5-image-to-video",
            "kling-v1-image-to-video",
            "kling-image-generation-v3",
            "kling-image-generation-v2-1",
            "kling-image-generation-v1"
    );
    private static final Set<String> LEGACY_VOLCENGINE_MODEL_CONFIG_CODES = Set.of(
            "volcengine-seedance",
            "seedance_video_generation",
            "volcengine-seedream"
    );
    private static final Set<String> LEGACY_VOLCENGINE_TOOL_CODES = Set.of(
            "seedance2_0_2",
            "doubao-seedream-image-generation",
            "seedance_video_generation",
            "volcengine-seedance",
            "volcengine-seedream"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SystemSettingService systemSettingService;
    private final AgentModelConfigService agentModelConfigService;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentToolDescriptorExtensionMapper agentToolDescriptorExtensionMapper;
    private final ToolService toolService;
    private final WorkflowService workflowService;
    private final ToolMapper toolMapper;
    private final ToolFieldSchemaMapper toolFieldSchemaMapper;
    private final ToolCategoryMapper toolCategoryMapper;
    private final ToolPromptVersionMapper toolPromptVersionMapper;
    private final ModelProviderRegistry modelProviderRegistry;
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final ModelVendorAccountService modelVendorAccountService;
    private final BypassCacheService bypassCacheService;
    private final TransactionTemplate transactionTemplate;
    private final PricingRuleMapper pricingRuleMapper;

    public ConfigBundleServiceImpl(SystemSettingService systemSettingService,
                                   AgentModelConfigService agentModelConfigService,
                                   AgentModelConfigMapper agentModelConfigMapper,
                                   AgentToolDescriptorExtensionMapper agentToolDescriptorExtensionMapper,
                                   ToolService toolService,
                                   WorkflowService workflowService,
                                   ToolMapper toolMapper,
                                   ToolFieldSchemaMapper toolFieldSchemaMapper,
                                   ToolCategoryMapper toolCategoryMapper,
                                   ToolPromptVersionMapper toolPromptVersionMapper,
                                   ModelProviderRegistry modelProviderRegistry,
                                   ModelVendorAccountMapper vendorAccountMapper,
                                   ModelVendorAccountService modelVendorAccountService,
                                   BypassCacheService bypassCacheService,
                                   TransactionTemplate transactionTemplate,
                                   PricingRuleMapper pricingRuleMapper) {
        this.systemSettingService = systemSettingService;
        this.agentModelConfigService = agentModelConfigService;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentToolDescriptorExtensionMapper = agentToolDescriptorExtensionMapper;
        this.toolService = toolService;
        this.workflowService = workflowService;
        this.toolMapper = toolMapper;
        this.toolFieldSchemaMapper = toolFieldSchemaMapper;
        this.toolCategoryMapper = toolCategoryMapper;
        this.toolPromptVersionMapper = toolPromptVersionMapper;
        this.modelProviderRegistry = modelProviderRegistry;
        this.vendorAccountMapper = vendorAccountMapper;
        this.modelVendorAccountService = modelVendorAccountService;
        this.bypassCacheService = bypassCacheService;
        this.transactionTemplate = transactionTemplate;
        this.pricingRuleMapper = pricingRuleMapper;
    }

    @Override
    public ConfigBundleDto exportBundle(Long operatorId, boolean includeSecrets) {
        return exportBundle(operatorId, includeSecrets, List.of(), true);
    }

    @Override
    public ConfigBundleDto exportBundle(Long operatorId,
                                        boolean includeSecrets,
                                        List<String> toolCodes,
                                        boolean includeMediaAssets) {
        Set<String> selectedToolCodes = normalizeToolCodes(toolCodes);
        boolean selective = !selectedToolCodes.isEmpty();

        List<ToolCategoryResponse> allCategories = toolService.adminCategories();
        List<AgentModelConfigResponse> allModelConfigs = agentModelConfigService.adminList();
        Map<Long, String> categoryCodesById = allCategories.stream()
                .collect(Collectors.toMap(ToolCategoryResponse::id, ToolCategoryResponse::categoryCode, (a, b) -> a));
        Map<Long, String> modelCodesById = allModelConfigs.stream()
                .collect(Collectors.toMap(AgentModelConfigResponse::id, this::stableModelConfigCode, (a, b) -> a));

        List<ToolSummaryResponse> tools = exportAllTools().stream()
                .filter(tool -> !selective || selectedToolCodes.contains(normalizeCode(tool.toolCode())))
                .toList();
        List<ConfigBundleDto.Tool> exportedTools = tools.stream()
                .map(tool -> exportTool(tool, categoryCodesById, modelCodesById, includeMediaAssets))
                .toList();

        Set<Long> exportedCategoryIds = tools.stream()
                .map(ToolSummaryResponse::categoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> exportedModelCodes = exportedTools.stream()
                .map(ConfigBundleDto.Tool::modelConfigCode)
                .filter(code -> !isBlank(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        exportedTools.stream()
                .map(ConfigBundleDto.Tool::workflow)
                .forEach(workflow -> collectWorkflowModelConfigIds(workflow).stream()
                        .map(modelCodesById::get)
                        .filter(code -> !isBlank(code))
                        .forEach(exportedModelCodes::add));

        List<AgentModelConfigResponse> modelConfigs = selective
                ? allModelConfigs.stream()
                .filter(config -> exportedModelCodes.contains(stableModelConfigCode(config)))
                .toList()
                : allModelConfigs;
        Set<Long> exportedVendorAccountIds = modelConfigs.stream()
                .map(AgentModelConfigResponse::vendorAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ModelVendorAccount> vendorAccounts = vendorAccountMapper.findAllActive().stream()
                .filter(account -> !selective || exportedVendorAccountIds.contains(account.getId()))
                .toList();
        Map<Long, String> accountRefById = vendorAccounts.stream()
                .collect(Collectors.toMap(ModelVendorAccount::getId,
                        account -> accountRef(account.getVendorCode(), account.getAccountName()),
                        (a, b) -> a));
        List<ToolCategoryResponse> categories = selective
                ? allCategories.stream()
                .filter(category -> exportedCategoryIds.contains(category.id()))
                .toList()
                : allCategories;

        return new ConfigBundleDto(
                FORMAT,
                VERSION,
                OffsetDateTime.now().toString(),
                operatorId == null ? null : String.valueOf(operatorId),
                selective ? "SELECTED_TOOLS" : "FULL",
                !includeSecrets,
                selective ? Map.of() : redactSettings(systemSettingService.settings()),
                vendorAccounts.stream()
                        .map(account -> exportVendorAccount(account, includeSecrets))
                        .toList(),
                modelConfigs.stream()
                        .map(config -> exportModelConfig(config, includeSecrets, accountRefById))
                        .toList(),
                categories.stream().map(this::exportCategory).toList(),
                exportedTools
        );
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConfigBundleImportResult importBundle(ConfigBundleDto bundle, Long operatorId) {
        if (bundle == null || bundle.format() == null || !FORMAT.equals(bundle.format())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported config bundle format");
        }

        List<String> warnings = new ArrayList<>();
        int settings = importSettings(bundle.settings());
        Map<String, Long> accountIdsByRef = importVendorAccounts(
                safeList(bundle.vendorAccounts()), warnings, bundle.secretsRedacted());
        ImportModelResult modelResult = importModelConfigs(
                safeList(bundle.modelConfigs()), accountIdsByRef, warnings, bundle.secretsRedacted());
        int categories = importCategories(safeList(bundle.categories()));

        Map<String, Long> modelIdsByCode = agentModelConfigService.adminList().stream()
                .filter(config -> stableModelConfigCode(config) != null)
                .collect(Collectors.toMap(this::stableModelConfigCode, AgentModelConfigResponse::id, (a, b) -> a));
        modelIdsByCode.putAll(modelResult.modelIdsByImportedCode);
        Map<String, Long> categoryIdsByCode = toolService.adminCategories().stream()
                .collect(Collectors.toMap(ToolCategoryResponse::categoryCode, ToolCategoryResponse::id, (a, b) -> a));

        ImportCounter counter = new ImportCounter(
                settings,
                (int) accountIdsByRef.values().stream().distinct().count(),
                modelResult.changed,
                categories);
        for (ConfigBundleDto.Tool tool : safeList(bundle.tools())) {
            try {
                Long toolIdToPublish = transactionTemplate.execute(status ->
                        importTool(tool, operatorId, modelIdsByCode, categoryIdsByCode, counter, warnings));
                if (toolIdToPublish != null) {
                    try {
                        toolService.publishTool(toolIdToPublish, operatorId);
                    } catch (BusinessException exception) {
                        warnings.add("Tool " + tool.toolCode()
                                + " kept as draft because it could not be published: "
                                + importPublishWarningMessage(exception));
                    }
                }
            } catch (Exception exception) {
                String toolCode = tool == null || isBlank(tool.toolCode()) ? "<missing>" : tool.toolCode();
                warnings.add("Tool " + toolCode + " import failed: " + rootMessage(exception));
            }
        }

        if (!isSelectedToolExport(bundle)) {
            pruneStaleCatalogAfterImport(bundle, accountIdsByRef, modelResult.modelIdsByImportedCode, warnings);
        }

        bypassCacheService.invalidateImportedCatalogData();

        return new ConfigBundleImportResult(
                counter.settings,
                counter.vendorAccounts,
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

    private boolean isSelectedToolExport(ConfigBundleDto bundle) {
        return bundle != null && "SELECTED_TOOLS".equalsIgnoreCase(bundle.exportScope());
    }

    private ConfigBundleDto.Tool exportTool(ToolSummaryResponse tool,
                                            Map<Long, String> categoryCodesById,
                                            Map<Long, String> modelCodesById,
                                            boolean includeMediaAssets) {
        AgentToolDescriptorExtension extension = agentToolDescriptorExtensionMapper
                .findByToolCode(tool.toolCode())
                .orElse(null);
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
                includeMediaAssets ? tool.coverUrl() : "",
                tool.toolType(),
                tool.inputModality(),
                tool.outputModality(),
                exportConfigNote(tool.configNote(), includeMediaAssets),
                tool.status(),
                tool.estimatedCreditCost(),
                modelCodesById.get(tool.modelConfigId()),
                tool.executionHandler(),
                extension == null ? true : Boolean.TRUE.equals(extension.getAgentEnabled()),
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

    private Set<String> normalizeToolCodes(List<String> toolCodes) {
        if (toolCodes == null || toolCodes.isEmpty()) {
            return Set.of();
        }
        return toolCodes.stream()
                .filter(Objects::nonNull)
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(this::normalizeCode)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String exportConfigNote(String configNote, boolean includeMediaAssets) {
        if (includeMediaAssets || configNote == null || configNote.isBlank()) {
            return configNote;
        }
        ToolFrontendStyleConfig style = ToolFrontendStyleConfig.fromConfigNote(configNote, OBJECT_MAPPER);
        ToolFrontendStyleConfig sanitized = new ToolFrontendStyleConfig(
                style.primaryColor(),
                style.welcomeMessage(),
                style.mediaDisplayMode(),
                "",
                "",
                "",
                "",
                style.heroTitle(),
                style.heroSubtitle(),
                List.of(),
                style.useCases(),
                style.steps(),
                style.recommendedToolCodes(),
                "",
                ""
        );
        return ToolFrontendStyleConfig.replaceOrAppendFrontendStyleMarker(configNote, sanitized, OBJECT_MAPPER);
    }

    private Set<Long> collectWorkflowModelConfigIds(ConfigBundleDto.Workflow workflow) {
        if (workflow == null) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        collectModelConfigIdsFromJson(workflow.configJson(), result);
        collectModelConfigIdsFromJson(workflow.nodesJson(), result);
        return result;
    }

    private void collectModelConfigIdsFromJson(String json, Set<Long> result) {
        if (isBlank(json)) {
            return;
        }
        try {
            collectModelConfigIds(OBJECT_MAPPER.readTree(json), result, false);
        } catch (Exception ignored) {
            // A malformed legacy workflow should not block exporting the rest of the bundle.
        }
    }

    private void collectModelConfigIds(JsonNode node, Set<Long> result, boolean insideModelConfigValue) {
        if (node == null || node.isNull()) {
            return;
        }
        if (insideModelConfigValue) {
            if (node.isIntegralNumber()) {
                result.add(node.longValue());
                return;
            }
            if (node.isTextual()) {
                try {
                    result.add(Long.parseLong(node.asText().trim()));
                } catch (NumberFormatException ignored) {
                    return;
                }
            }
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                boolean isModelConfigKey = "modelConfigId".equals(key) || "modelConfigIds".equals(key);
                collectModelConfigIds(entry.getValue(), result, insideModelConfigValue || isModelConfigKey);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectModelConfigIds(item, result, insideModelConfigValue);
            }
        }
    }

    private ConfigBundleDto.VendorAccount exportVendorAccount(ModelVendorAccount account, boolean includeSecrets) {
        String ref = accountRef(account.getVendorCode(), account.getAccountName());
        return new ConfigBundleDto.VendorAccount(
                account.getVendorCode(),
                account.getVendorCode(),
                null,
                null,
                account.getAccountName(),
                ref,
                account.getBaseUrl(),
                includeSecrets ? nullToEmpty(account.getApiKey()) : "",
                includeSecrets ? nullToEmpty(account.getExtraAuthJson()) : "",
                !includeSecrets,
                account.getConsoleUrl(),
                account.getBalanceUrl(),
                account.getBalanceQueryMode(),
                account.getBalanceAmount(),
                account.getBalanceCurrency(),
                account.getBalanceLowThreshold(),
                account.getEnabled()
        );
    }

    private ConfigBundleDto.ModelConfig exportModelConfig(AgentModelConfigResponse config,
                                                          boolean includeSecrets,
                                                          Map<Long, String> accountRefById) {
        AgentModelConfig secretSource = includeSecrets && config.id() != null
                ? agentModelConfigMapper.findActiveById(config.id())
                : null;
        String vendorAccountRef = config.vendorAccountId() == null
                ? null
                : accountRefById.get(config.vendorAccountId());
        String modelApiKey = "";
        String modelExtraAuth = "";
        String executionOptionsJson = secretSource == null ? null : secretSource.getExecutionOptionsJson();
        if (includeSecrets && secretSource != null) {
            if (vendorAccountRef != null && config.vendorAccountId() != null) {
                ModelVendorAccount account = vendorAccountMapper.findActiveById(config.vendorAccountId());
                if (account != null) {
                    modelApiKey = "";
                    modelExtraAuth = "";
                } else {
                    modelApiKey = nullToEmpty(secretSource.getApiKey());
                    modelExtraAuth = nullToEmpty(secretSource.getExtraAuthJson());
                }
            } else {
                modelApiKey = nullToEmpty(secretSource.getApiKey());
                modelExtraAuth = nullToEmpty(secretSource.getExtraAuthJson());
            }
        }
        return new ConfigBundleDto.ModelConfig(
                config.displayName(),
                stableModelConfigCode(config),
                vendorAccountRef,
                null,
                null,
                null,
                config.provider(),
                config.modelName(),
                config.baseUrl(),
                modelApiKey,
                modelExtraAuth,
                config.executionTask(),
                executionOptionsJson,
                !includeSecrets,
                config.minimaxGroupId(),
                config.consoleUrl(),
                config.balanceUrl(),
                config.docsUrl(),
                config.timeoutSeconds(),
                config.connectTimeoutSeconds(),
                config.readTimeoutSeconds(),
                config.inputTokenPricePer1k(),
                config.outputTokenPricePer1k(),
                config.inputTokenPricePer1m(),
                config.outputTokenPricePer1m(),
                config.billingUnit(),
                config.unitPrice(),
                config.enabled(),
                config.agentEnabled(),
                config.isDefault(),
                config.capabilities(),
                exportModelPricingRules(config.id())
        );
    }

    private List<ConfigBundleDto.PricingRuleBundle> exportModelPricingRules(Long modelConfigId) {
        if (modelConfigId == null) {
            return List.of();
        }
        return pricingRuleMapper.findByModelScope(modelConfigId).stream()
                .map(this::exportPricingRule)
                .toList();
    }

    private ConfigBundleDto.PricingRuleBundle exportPricingRule(PricingRule rule) {
        return new ConfigBundleDto.PricingRuleBundle(
                rule.getParamKey(),
                rule.getRuleType(),
                rule.getMatchOp(),
                rule.getMatchValue(),
                rule.getFactor(),
                rule.getExtraCredits(),
                rule.getPriority(),
                rule.getEnabled(),
                rule.getRemark()
        );
    }

    private void importModelPricingRules(Long modelConfigId, List<ConfigBundleDto.PricingRuleBundle> rules) {
        if (modelConfigId == null || rules == null) {
            return;
        }
        pricingRuleMapper.deleteByModelScope(modelConfigId);
        for (ConfigBundleDto.PricingRuleBundle item : rules) {
            if (item == null || isBlank(item.paramKey())) {
                continue;
            }
            PricingRule entity = new PricingRule();
            entity.setScopeType("MODEL");
            entity.setScopeRef(modelConfigId);
            entity.setParamKey(item.paramKey().trim());
            entity.setRuleType(isBlank(item.ruleType()) ? "MULTIPLIER" : item.ruleType().trim().toUpperCase());
            entity.setMatchOp(isBlank(item.matchOp()) ? "EQ" : item.matchOp().trim().toUpperCase());
            entity.setMatchValue(item.matchValue());
            entity.setFactor(item.factor() == null || item.factor().compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ONE
                    : item.factor());
            entity.setExtraCredits(item.extraCredits() == null ? 0 : Math.max(0, item.extraCredits()));
            entity.setPriority(item.priority() == null ? 100 : item.priority());
            entity.setEnabled(item.enabled() == null ? Boolean.TRUE : item.enabled());
            entity.setRemark(item.remark());
            pricingRuleMapper.insert(entity);
        }
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
                workflow.status(),
                workflow.version(),
                null,
                null,
                null,
                null
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

    private Map<String, Long> importVendorAccounts(List<ConfigBundleDto.VendorAccount> accounts,
                                                   List<String> warnings,
                                                   Boolean bundleSecretsRedacted) {
        Map<String, Long> accountIdsByRef = new LinkedHashMap<>();
        for (ConfigBundleDto.VendorAccount item : accounts) {
            if (isBlank(item.vendorCode()) || isBlank(item.accountName())) {
                warnings.add("Skipped vendor account with missing vendorCode/accountName");
                continue;
            }
            String vendorCode = item.vendorCode().trim().toLowerCase(Locale.ROOT);
            String accountName = item.accountName().trim();
            String ref = isBlank(item.accountRef()) ? accountRef(vendorCode, accountName) : item.accountRef().trim();
            if ("mineru".equals(vendorCode)) {
                warnings.add("Skipped vendor account import ref " + ref
                        + ": MinerU belongs to System Settings -> Engine API, not model vendor accounts");
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    boolean secretsRedacted = item.secretsRedacted() != null
                            ? item.secretsRedacted()
                            : bundleSecretsRedacted == null || bundleSecretsRedacted;
                    String extraAuthJson = secretsRedacted
                            ? null
                            : cleanExtraAuthJson(item.extraAuthJson(), warnings, "vendor account " + ref);
                    ModelVendorAccountRequest request = new ModelVendorAccountRequest(
                            vendorCode,
                            accountName,
                            item.baseUrl(),
                            secretsRedacted ? null : nullToEmpty(item.apiKey()),
                            null,
                            extraAuthJson,
                            null,
                            item.consoleUrl(),
                            item.balanceUrl(),
                            null,
                            null,
                            item.balanceQueryMode(),
                            item.balanceAmount(),
                            item.balanceCurrency(),
                            item.balanceLowThreshold(),
                            item.enabled(),
                            null,
                            null
                    );
                    ModelVendorAccount existing = resolveExistingVendorAccount(
                            vendorCode, accountName, item.baseUrl(), request.apiKey(), request.extraAuthJson(), ref, warnings);
                    ModelVendorAccountResponse saved = existing == null
                            ? modelVendorAccountService.adminCreate(request)
                            : modelVendorAccountService.adminUpdate(existing.getId(), request);
                    accountIdsByRef.put(ref, saved.id());
                    removeDuplicateVendorAccounts(
                            vendorCode, item.baseUrl(), request.apiKey(), request.extraAuthJson(), saved.id(), warnings);
                });
            } catch (Exception exception) {
                warnings.add("Vendor account " + ref + " import failed: " + rootMessage(exception));
            }
        }
        return accountIdsByRef;
    }

    private ImportModelResult importModelConfigs(List<ConfigBundleDto.ModelConfig> configs,
                                                 Map<String, Long> accountIdsByRef,
                                                 List<String> warnings,
                                                 Boolean bundleSecretsRedacted) {
        int count = 0;
        Map<String, Long> modelIdsByImportedCode = new LinkedHashMap<>();
        for (ConfigBundleDto.ModelConfig config : configs) {
            if (isBlank(config.configCode()) || isBlank(config.provider()) || isBlank(config.modelName())) {
                warnings.add("Skipped model config with missing configCode/provider/modelName");
                continue;
            }
            if (LEGACY_KLING_MODEL_CONFIG_CODES.contains(config.configCode().trim())) {
                warnings.add("Skipped legacy Kling model config " + config.configCode()
                        + ": use consolidated kling-gateway-* configs instead");
                continue;
            }
            if (isLegacyVolcengineModelConfigCode(config.configCode())) {
                warnings.add("Skipped legacy Volcengine model config " + config.configCode()
                        + ": use consolidated volcengine-gateway-* configs instead");
                continue;
            }
            if (!modelProviderRegistry.isSupported(config.provider())) {
                warnings.add("Skipped model config " + config.configCode()
                        + ": unsupported provider " + config.provider());
                continue;
            }
            try {
                Boolean changed = transactionTemplate.execute(status -> {
                    boolean secretsRedacted = config.secretsRedacted() != null
                            ? config.secretsRedacted()
                            : bundleSecretsRedacted == null || bundleSecretsRedacted;
                    Long vendorAccountId = null;
                    if (!isBlank(config.vendorAccountRef())) {
                        vendorAccountId = accountIdsByRef.get(config.vendorAccountRef().trim());
                        if (vendorAccountId == null) {
                            warnings.add("Model config " + config.configCode()
                                    + ": vendorAccountRef not found -> " + config.vendorAccountRef());
                        }
                    }
                    AgentModelConfig existing = agentModelConfigMapper.findActiveByConfigCode(config.configCode());
                    if (existing == null) {
                        Optional<AgentModelConfigResponse> equivalent = findEquivalentModelConfig(config);
                        if (equivalent.isPresent()) {
                            modelIdsByImportedCode.put(config.configCode(), equivalent.get().id());
                            warnings.add("Reused existing model config " + stableModelConfigCode(equivalent.get())
                                    + " for imported model config " + config.configCode());
                            return false;
                        }
                    }
                    boolean forceDisabled = shouldDisableImportedModel(config, vendorAccountId, secretsRedacted, warnings);
                    AgentModelConfigRequest request = modelConfigRequest(config, vendorAccountId, secretsRedacted, forceDisabled);
                    if (existing == null) {
                        AgentModelConfigResponse created = agentModelConfigService.adminCreate(request);
                        modelIdsByImportedCode.put(config.configCode(), created.id());
                    } else {
                        AgentModelConfigResponse updated = agentModelConfigService.adminUpdate(existing.getId(), request);
                        modelIdsByImportedCode.put(config.configCode(), updated.id());
                    }
                    return true;
                });
                if (Boolean.TRUE.equals(changed)) {
                    count++;
                }
            } catch (Exception exception) {
                warnings.add("Model config " + config.configCode() + " import failed: " + rootMessage(exception));
            }
            Long importedModelId = modelIdsByImportedCode.get(config.configCode());
            if (importedModelId != null && config.pricingRules() != null) {
                try {
                    transactionTemplate.executeWithoutResult(status -> importModelPricingRules(importedModelId, config.pricingRules()));
                } catch (Exception exception) {
                    warnings.add("Model config " + config.configCode() + " pricing rules import failed: " + rootMessage(exception));
                }
            }
        }
        return new ImportModelResult(count, modelIdsByImportedCode);
    }

    private AgentModelConfigRequest modelConfigRequest(ConfigBundleDto.ModelConfig config,
                                                       Long vendorAccountId,
                                                       boolean secretsRedacted,
                                                       boolean forceDisabled) {
        return new AgentModelConfigRequest(
                vendorAccountId,
                config.displayName(),
                config.configCode(),
                config.provider(),
                config.modelName(),
                config.baseUrl(),
                secretsRedacted ? null : nullToEmpty(config.apiKey()),
                null,
                secretsRedacted ? null : cleanExtraAuthJson(config.extraAuthJson(), null, "model config " + config.configCode()),
                config.executionTask(),
                config.executionOptionsJson(),
                config.minimaxGroupId(),
                config.consoleUrl(),
                config.balanceUrl(),
                config.docsUrl(),
                config.timeoutSeconds(),
                config.connectTimeoutSeconds(),
                config.readTimeoutSeconds(),
                config.inputTokenPricePer1k(),
                config.outputTokenPricePer1k(),
                config.inputTokenPricePer1m(),
                config.outputTokenPricePer1m(),
                config.billingUnit(),
                config.unitPrice(),
                forceDisabled ? false : config.enabled(),
                forceDisabled ? Boolean.FALSE : config.agentEnabled(),
                config.isDefault(),
                config.capabilities(),
                null,
                null
        );
    }

    private boolean shouldDisableImportedModel(ConfigBundleDto.ModelConfig config,
                                               Long vendorAccountId,
                                               boolean secretsRedacted,
                                               List<String> warnings) {
        boolean requestedEnabled = config.enabled() == null || config.enabled()
                || config.agentEnabled() == null || config.agentEnabled();
        if (!requestedEnabled) {
            return false;
        }
        if (vendorAccountId == null) {
            boolean hasInlineCredential = !secretsRedacted && (hasSecret(config.apiKey()) || hasSecret(config.extraAuthJson()));
            if (!hasInlineCredential && !"mock".equalsIgnoreCase(config.provider())) {
                warnings.add("Imported model config " + config.configCode()
                        + " as disabled: credential is not configured");
                return true;
            }
            return false;
        }
        ModelVendorAccount account = vendorAccountMapper.findActiveById(vendorAccountId);
        if (account == null) {
            return false;
        }
        if (Boolean.FALSE.equals(account.getEnabled())) {
            warnings.add("Imported model config " + config.configCode()
                    + " as disabled: vendor account is disabled");
            return true;
        }
        boolean acceptOnly = modelProviderRegistry.findByCode(config.provider())
                .map(provider -> "accept_only".equalsIgnoreCase(provider.testStrategy()))
                .orElse(false);
        if (acceptOnly) {
            boolean hasCredential = hasSecret(account.getApiKey()) || hasSecret(account.getExtraAuthJson());
            if (!hasCredential) {
                warnings.add("Imported model config " + config.configCode()
                        + " as disabled: vendor account credential is not configured");
            }
            return !hasCredential;
        }
        String health = account.getHealthStatus() == null ? "" : account.getHealthStatus().trim();
        if (!"OK".equalsIgnoreCase(health)) {
            warnings.add("Imported model config " + config.configCode()
                    + " as disabled: vendor account connectivity is not OK yet; run account test in admin");
            return true;
        }
        return false;
    }

    private boolean hasSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.startsWith("replace-with-") && !normalized.startsWith("sk-xxx");
    }

    private void pruneStaleCatalogAfterImport(ConfigBundleDto bundle,
                                              Map<String, Long> keptAccountIdsByRef,
                                              Map<String, Long> keptModelIdsByImportedCode,
                                              List<String> warnings) {
        Set<String> importedConfigCodes = safeList(bundle.modelConfigs()).stream()
                .map(ConfigBundleDto.ModelConfig::configCode)
                .filter(code -> !isBlank(code))
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> keptAccountIds = new HashSet<>(keptAccountIdsByRef.values());
        Set<Long> keptModelIds = new HashSet<>(keptModelIdsByImportedCode.values());
        for (AgentModelConfig config : agentModelConfigMapper.findAllActive()) {
            if (!isBlank(config.getConfigCode()) && importedConfigCodes.contains(config.getConfigCode().trim())) {
                keptModelIds.add(config.getId());
            }
        }

        for (AgentModelConfig config : List.copyOf(agentModelConfigMapper.findAllActive())) {
            if (keptModelIds.contains(config.getId())) {
                continue;
            }
            if (!isBlank(config.getConfigCode()) && importedConfigCodes.contains(config.getConfigCode().trim())) {
                continue;
            }
            if (modelHasValidCredential(config)) {
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> agentModelConfigService.adminDelete(config.getId()));
                warnings.add("Removed stale model config without credential: " + describeModelConfig(config));
            } catch (Exception exception) {
                warnings.add("Could not remove stale model config " + describeModelConfig(config)
                        + ": " + rootMessage(exception));
            }
        }

        for (ModelVendorAccount account : List.copyOf(vendorAccountMapper.findAllActive())) {
            if (keptAccountIds.contains(account.getId())) {
                continue;
            }
            if (accountHasValidCredential(account)) {
                continue;
            }
            int modelCount = vendorAccountMapper.countActiveModelsByAccountId(account.getId());
            if (modelCount > 0) {
                warnings.add("Skipped stale vendor account #" + account.getId()
                        + " (" + account.getAccountName() + "): still referenced by " + modelCount + " model(s)");
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> vendorAccountMapper.softDelete(account.getId()));
                warnings.add("Removed stale vendor account without credential: "
                        + accountRef(account.getVendorCode(), account.getAccountName()));
            } catch (Exception exception) {
                warnings.add("Could not remove stale vendor account #"
                        + account.getId() + ": " + rootMessage(exception));
            }
        }
    }

    private boolean modelHasValidCredential(AgentModelConfig config) {
        if (config == null) {
            return false;
        }
        if ("mock".equalsIgnoreCase(config.getProvider())) {
            return false;
        }
        if (config.getVendorAccountId() != null) {
            ModelVendorAccount account = vendorAccountMapper.findActiveById(config.getVendorAccountId());
            return account != null && accountHasValidCredential(account);
        }
        return hasSecret(config.getApiKey()) || hasSecret(config.getExtraAuthJson());
    }

    private boolean accountHasValidCredential(ModelVendorAccount account) {
        if (account == null) {
            return false;
        }
        return hasSecret(account.getApiKey()) || hasSecret(account.getExtraAuthJson());
    }

    private String describeModelConfig(AgentModelConfig config) {
        if (config == null) {
            return "<missing>";
        }
        String code = isBlank(config.getConfigCode()) ? "model_" + config.getId() : config.getConfigCode().trim();
        return code + " (#" + config.getId() + ")";
    }

    private String cleanExtraAuthJson(String json, List<String> warnings, String subject) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(json);
            if (!parsed.isObject()) {
                return json.trim();
            }
            ObjectNode copy = ((ObjectNode) parsed).deepCopy();
            boolean changed = removeMetadataFields(copy);
            if (copy.isEmpty()) {
                if (changed && warnings != null) {
                    warnings.add("Removed non-auth metadata from " + subject + " extraAuthJson");
                }
                return "";
            }
            if (changed && warnings != null) {
                warnings.add("Cleaned non-auth metadata from " + subject + " extraAuthJson");
            }
            return copy.toString();
        } catch (Exception ignored) {
            return json.trim();
        }
    }

    private boolean removeMetadataFields(ObjectNode node) {
        boolean changed = false;
        for (String key : EXTRA_AUTH_METADATA_KEYS) {
            if (node.has(key)) {
                node.remove(key);
                changed = true;
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().isObject()) {
                changed = removeMetadataFields((ObjectNode) entry.getValue()) || changed;
            }
        }
        return changed;
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

    private Long importTool(ConfigBundleDto.Tool item,
                            Long operatorId,
                            Map<String, Long> modelIdsByCode,
                            Map<String, Long> categoryIdsByCode,
                            ImportCounter counter,
                            List<String> warnings) {
        if (isBlank(item.toolCode()) || isBlank(item.toolName())) {
            warnings.add("Skipped tool with missing toolCode/toolName");
            return null;
        }
        if (LEGACY_KLING_TOOL_CODES.contains(item.toolCode().trim())) {
            warnings.add("Skipped legacy Kling tool " + item.toolCode()
                    + ": use consolidated kling-* gateway tools instead");
            return null;
        }
        if (isLegacyVolcengineToolCode(item.toolCode())) {
            warnings.add("Skipped legacy Volcengine tool " + item.toolCode()
                    + ": use consolidated volcengine-* gateway tools instead");
            return null;
        }
        Long categoryId = categoryIdsByCode.get(item.categoryCode());
        if (categoryId == null) {
            warnings.add("Skipped tool " + item.toolCode() + ": categoryCode not found: " + item.categoryCode());
            return null;
        }
        Long modelConfigId = isBlank(item.modelConfigCode()) ? null : modelIdsByCode.get(item.modelConfigCode());
        if (!isBlank(item.modelConfigCode()) && modelConfigId == null) {
            warnings.add("Tool " + item.toolCode() + " imported without model binding; modelConfigCode not found: " + item.modelConfigCode());
        }

        AiTool existing = findImportTargetTool(item.toolCode(), operatorId, warnings).orElse(null);
        String coverUrl = importedCoverUrl(item, existing, warnings);
        String configNote = item.configNote();
        if (existing != null) {
            configNote = ConfigNoteMergeSupport.mergePreservingMediaUrls(
                    existing.getConfigNote(), configNote, OBJECT_MAPPER);
        }

        boolean restoreDisabledModelBinding = isDisabledModelConfig(modelConfigId);
        UpsertToolRequest request = new UpsertToolRequest(
                item.toolCode(),
                item.toolName(),
                categoryId,
                item.description(),
                coverUrl,
                item.toolType(),
                item.inputModality(),
                item.outputModality(),
                configNote,
                item.estimatedCreditCost() == null ? 0 : item.estimatedCreditCost(),
                restoreDisabledModelBinding ? null : modelConfigId,
                item.executionHandler(),
                null
        );
        ToolSummaryResponse saved = existing == null
                ? toolService.createTool(request, operatorId)
                : toolService.updateTool(existing.getId(), request, operatorId);
        if (restoreDisabledModelBinding) {
            toolMapper.updateToolModelConfig(saved.id(), modelConfigId, operatorId);
            warnings.add("Restored disabled model binding for tool " + item.toolCode()
                    + "; enable and test the model before runtime use");
        }
        counter.tools++;
        if (item.agentEnabled() != null) {
            upsertAgentToolAccess(saved, item.agentEnabled());
        }

        if (!safeList(item.fields()).isEmpty()) {
            ensureActiveFieldSchema(saved.id(), operatorId, item.toolCode(), warnings);
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
            return saved.id();
        } else if ("OFFLINE".equals(status)) {
            toolService.offlineTool(saved.id(), operatorId);
        }
        return null;
    }

    private String importedCoverUrl(ConfigBundleDto.Tool item, AiTool existing, List<String> warnings) {
        if (!isBlank(item.coverUrl())) {
            return item.coverUrl();
        }
        if (existing == null || isBlank(existing.getCoverUrl())) {
            return item.coverUrl();
        }
        warnings.add("Preserved existing coverUrl for tool " + item.toolCode()
                + " because imported coverUrl was empty");
        return existing.getCoverUrl();
    }

    private String importPublishWarningMessage(BusinessException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        if (message.startsWith("bound model config has no API key: ")) {
            int hintStart = message.indexOf(". 请在管理端");
            if (hintStart > 0) {
                return message.substring(0, hintStart);
            }
        }
        return message;
    }

    private boolean isDisabledModelConfig(Long modelConfigId) {
        if (modelConfigId == null) {
            return false;
        }
        AgentModelConfig config = agentModelConfigMapper.findActiveById(modelConfigId);
        return config != null && Boolean.FALSE.equals(config.getEnabled());
    }

    private void ensureActiveFieldSchema(Long toolId, Long operatorId, String toolCode, List<String> warnings) {
        if (toolFieldSchemaMapper.findActiveSchemaId(toolId).isPresent()) {
            return;
        }
        toolFieldSchemaMapper.createActiveDefaultSchema(toolId, operatorId);
        warnings.add("Created missing field schema for tool " + toolCode + " before importing fields");
    }

    private Optional<AiTool> findImportTargetTool(String toolCode, Long operatorId, List<String> warnings) {
        Optional<AiTool> active = findToolByCode(toolCode);
        if (active.isPresent()) {
            return active;
        }
        Optional<AiTool> any = toolMapper.findAnyByCode(toolCode);
        if (any.isEmpty()) {
            return Optional.empty();
        }
        AiTool tool = any.get();
        if (Boolean.TRUE.equals(tool.getDeleted())) {
            toolMapper.restoreDeletedTool(tool.getId(), operatorId);
            warnings.add("Restored soft-deleted tool " + toolCode + " before import");
        }
        return toolMapper.findById(tool.getId()).or(() -> Optional.of(tool));
    }

    private void upsertAgentToolAccess(ToolSummaryResponse tool, Boolean agentEnabled) {
        AgentToolDescriptorExtension extension = agentToolDescriptorExtensionMapper
                .findByToolCode(tool.toolCode())
                .orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (extension == null) {
            extension = new AgentToolDescriptorExtension();
            extension.setToolId(tool.id());
            extension.setToolCode(tool.toolCode());
            extension.setAgentRecommendable(true);
            extension.setAgentAutoCallable(false);
            extension.setConfirmationPolicy("auto");
            extension.setRiskLevel("low");
            extension.setOutputType(normalizeOutputType(tool.outputModality()));
            extension.setCreatedAt(now);
        } else {
            extension.setToolId(tool.id());
        }
        extension.setAgentEnabled(Boolean.TRUE.equals(agentEnabled));
        extension.setUpdatedAt(now);
        if (extension.getId() == null) {
            agentToolDescriptorExtensionMapper.insert(extension);
        } else {
            agentToolDescriptorExtensionMapper.updateById(extension);
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
        if (workflow == null || isBlank(workflow.workflowName())) {
            return;
        }
        String nodesJson = workflow.resolvedNodesJson();
        String edgesJson = workflow.resolvedEdgesJson();
        if (isBlank(nodesJson) || isBlank(edgesJson)) {
            return;
        }
        WorkflowResponse current = workflowService.getWorkflow(toolId);
        WorkflowResponse saved = workflowService.saveWorkflow(toolId, new UpsertWorkflowRequest(
                workflow.workflowName(),
                nodesJson,
                edgesJson,
                workflow.resolvedGroupsJson(),
                workflow.resolvedConfigJson(),
                workflow.status(),
                current == null ? 0L : current.draftRevision()
        ), operatorId);
        if ("PUBLISHED".equalsIgnoreCase(workflow.status())) {
            workflowService.publish(saved.id(), operatorId);
        }
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
        if (!isLegacyGeneratedModelConfigCode(imported.configCode())) {
            return Optional.empty();
        }
        return agentModelConfigService.adminList().stream()
                .filter(existing -> sameModelIdentity(existing, imported))
                .findFirst();
    }

    private boolean isLegacyGeneratedModelConfigCode(String configCode) {
        if (isBlank(configCode)) {
            return true;
        }
        String normalized = configCode.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("\\d+") || normalized.matches("model_\\d+");
    }

    private boolean isLegacyVolcengineModelConfigCode(String configCode) {
        if (isBlank(configCode)) {
            return false;
        }
        String normalized = configCode.trim().toLowerCase(Locale.ROOT);
        if ("volcengine-gateway-video".equals(normalized) || "volcengine-gateway-image".equals(normalized)) {
            return false;
        }
        return LEGACY_VOLCENGINE_MODEL_CONFIG_CODES.contains(normalized)
                || normalized.startsWith("volcengine-seedance-")
                || normalized.startsWith("volcengine-seedream-");
    }

    private boolean isLegacyVolcengineToolCode(String toolCode) {
        if (isBlank(toolCode)) {
            return false;
        }
        String normalized = toolCode.trim().toLowerCase(Locale.ROOT);
        if ("volcengine-video".equals(normalized) || "volcengine-image".equals(normalized)) {
            return false;
        }
        return LEGACY_VOLCENGINE_TOOL_CODES.contains(normalized)
                || normalized.startsWith("volcengine-seedance-")
                || normalized.startsWith("volcengine-seedream-");
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

    private String normalizeOutputType(String outputModality) {
        return outputModality == null || outputModality.isBlank() ? "text" : outputModality.trim().toLowerCase(Locale.ROOT);
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

    private ModelVendorAccount resolveExistingVendorAccount(String vendorCode,
                                                            String accountName,
                                                            String baseUrl,
                                                            String apiKey,
                                                            String extraAuthJson,
                                                            String ref,
                                                            List<String> warnings) {
        ModelVendorAccount existing = vendorAccountMapper.findActiveByVendorCodeAndAccountName(vendorCode, accountName);
        if (existing != null || isBlank(baseUrl)) {
            return existing;
        }
        String normalizedBaseUrl = normalizeVendorBaseUrl(baseUrl);
        ModelVendorAccount matchedByBaseUrl = vendorAccountMapper.findActiveByVendorCode(vendorCode).stream()
                .filter(account -> normalizedBaseUrl.equals(normalizeVendorBaseUrl(account.getBaseUrl())))
                .filter(account -> credentialCompatible(apiKey, account.getApiKey())
                        && credentialCompatible(extraAuthJson, account.getExtraAuthJson()))
                .max((left, right) -> {
                    int leftModels = vendorAccountMapper.countActiveModelsByAccountId(left.getId());
                    int rightModels = vendorAccountMapper.countActiveModelsByAccountId(right.getId());
                    if (leftModels != rightModels) {
                        return Integer.compare(leftModels, rightModels);
                    }
                    return Long.compare(left.getId(), right.getId());
                })
                .orElse(null);
        if (matchedByBaseUrl != null) {
            warnings.add("Merged vendor account import ref " + ref
                    + " into existing account #" + matchedByBaseUrl.getId()
                    + " (" + matchedByBaseUrl.getAccountName() + ") by matching baseUrl");
        }
        return matchedByBaseUrl;
    }

    private void removeDuplicateVendorAccounts(String vendorCode,
                                               String baseUrl,
                                               String apiKey,
                                               String extraAuthJson,
                                               Long keptAccountId,
                                               List<String> warnings) {
        if (isBlank(baseUrl) || keptAccountId == null) {
            return;
        }
        String normalizedBaseUrl = normalizeVendorBaseUrl(baseUrl);
        for (ModelVendorAccount account : vendorAccountMapper.findActiveByVendorCode(vendorCode)) {
            if (keptAccountId.equals(account.getId())) {
                continue;
            }
            if (!normalizedBaseUrl.equals(normalizeVendorBaseUrl(account.getBaseUrl()))) {
                continue;
            }
            if (!credentialCompatible(apiKey, account.getApiKey())
                    || !credentialCompatible(extraAuthJson, account.getExtraAuthJson())) {
                continue;
            }
            int modelCount = vendorAccountMapper.countActiveModelsByAccountId(account.getId());
            reassignVendorAccountModels(account.getId(), keptAccountId);
            vendorAccountMapper.softDelete(account.getId());
            warnings.add("Removed duplicate vendor account #" + account.getId()
                    + " (" + account.getAccountName() + ") with same baseUrl as account #" + keptAccountId
                    + (modelCount > 0 ? "; reassigned " + modelCount + " model(s)" : ""));
        }
    }

    private void reassignVendorAccountModels(Long fromAccountId, Long toAccountId) {
        if (fromAccountId == null || toAccountId == null || fromAccountId.equals(toAccountId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (AgentModelConfig config : agentModelConfigMapper.findAllActive()) {
            if (fromAccountId.equals(config.getVendorAccountId())) {
                agentModelConfigMapper.updateVendorAccountId(config.getId(), toAccountId, now);
            }
        }
    }

    private static boolean credentialCompatible(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        return a.isEmpty() || b.isEmpty() || a.equals(b);
    }

    private static String accountRef(String vendorCode, String accountName) {
        return vendorCode.trim().toLowerCase(Locale.ROOT) + "::" + accountName.trim();
    }

    private static String normalizeVendorBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String normalized = baseUrl.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "unknown error" : throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }

    private static final class ImportCounter {
        private final int settings;
        private final int vendorAccounts;
        private final int modelConfigs;
        private final int categories;
        private int tools;
        private int fields;
        private int prompts;
        private int promptVersions;
        private int workflows;

        private ImportCounter(int settings, int vendorAccounts, int modelConfigs, int categories) {
            this.settings = settings;
            this.vendorAccounts = vendorAccounts;
            this.modelConfigs = modelConfigs;
            this.categories = categories;
        }
    }

    private record ImportModelResult(int changed, Map<String, Long> modelIdsByImportedCode) {
    }
}
