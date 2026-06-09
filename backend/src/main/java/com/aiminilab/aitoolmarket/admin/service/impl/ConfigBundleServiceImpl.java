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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SystemSettingService systemSettingService;
    private final AgentModelConfigService agentModelConfigService;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentToolDescriptorExtensionMapper agentToolDescriptorExtensionMapper;
    private final ToolService toolService;
    private final WorkflowService workflowService;
    private final ToolMapper toolMapper;
    private final ToolCategoryMapper toolCategoryMapper;
    private final ToolPromptVersionMapper toolPromptVersionMapper;
    private final ModelProviderRegistry modelProviderRegistry;
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final ModelVendorAccountService modelVendorAccountService;

    public ConfigBundleServiceImpl(SystemSettingService systemSettingService,
                                   AgentModelConfigService agentModelConfigService,
                                   AgentModelConfigMapper agentModelConfigMapper,
                                   AgentToolDescriptorExtensionMapper agentToolDescriptorExtensionMapper,
                                   ToolService toolService,
                                   WorkflowService workflowService,
                                   ToolMapper toolMapper,
                                   ToolCategoryMapper toolCategoryMapper,
                                   ToolPromptVersionMapper toolPromptVersionMapper,
                                   ModelProviderRegistry modelProviderRegistry,
                                   ModelVendorAccountMapper vendorAccountMapper,
                                   ModelVendorAccountService modelVendorAccountService) {
        this.systemSettingService = systemSettingService;
        this.agentModelConfigService = agentModelConfigService;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentToolDescriptorExtensionMapper = agentToolDescriptorExtensionMapper;
        this.toolService = toolService;
        this.workflowService = workflowService;
        this.toolMapper = toolMapper;
        this.toolCategoryMapper = toolCategoryMapper;
        this.toolPromptVersionMapper = toolPromptVersionMapper;
        this.modelProviderRegistry = modelProviderRegistry;
        this.vendorAccountMapper = vendorAccountMapper;
        this.modelVendorAccountService = modelVendorAccountService;
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

        List<ModelVendorAccount> vendorAccounts = vendorAccountMapper.findAllActive();
        Map<Long, String> accountRefById = vendorAccounts.stream()
                .collect(Collectors.toMap(ModelVendorAccount::getId,
                        account -> accountRef(account.getVendorCode(), account.getAccountName()),
                        (a, b) -> a));

        return new ConfigBundleDto(
                FORMAT,
                VERSION,
                OffsetDateTime.now().toString(),
                operatorId == null ? null : String.valueOf(operatorId),
                !includeSecrets,
                redactSettings(systemSettingService.settings()),
                vendorAccounts.stream()
                        .map(account -> exportVendorAccount(account, includeSecrets))
                        .toList(),
                agentModelConfigService.adminList().stream()
                        .map(config -> exportModelConfig(config, includeSecrets, accountRefById))
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
            importTool(tool, operatorId, modelIdsByCode, categoryIdsByCode, counter, warnings);
        }

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

    private ConfigBundleDto.Tool exportTool(ToolSummaryResponse tool,
                                            Map<Long, String> categoryCodesById,
                                            Map<Long, String> modelCodesById) {
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
                tool.coverUrl(),
                tool.toolType(),
                tool.inputModality(),
                tool.outputModality(),
                tool.configNote(),
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
            boolean secretsRedacted = item.secretsRedacted() != null
                    ? item.secretsRedacted()
                    : bundleSecretsRedacted == null || bundleSecretsRedacted;
            String extraAuthJson = secretsRedacted ? null : cleanExtraAuthJson(item.extraAuthJson(), warnings, "vendor account " + ref);
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
                    item.balanceQueryMode(),
                    item.balanceAmount(),
                    item.balanceCurrency(),
                    item.balanceLowThreshold(),
                    item.enabled()
            );
            ModelVendorAccount existing = resolveExistingVendorAccount(
                    vendorCode, accountName, item.baseUrl(), request.apiKey(), request.extraAuthJson(), ref, warnings);
            ModelVendorAccountResponse saved = existing == null
                    ? modelVendorAccountService.adminCreate(request)
                    : modelVendorAccountService.adminUpdate(existing.getId(), request);
            accountIdsByRef.put(ref, saved.id());
            removeDuplicateVendorAccounts(vendorCode, item.baseUrl(), request.apiKey(), request.extraAuthJson(), saved.id(), warnings);
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
            if (!modelProviderRegistry.isSupported(config.provider())) {
                warnings.add("Skipped model config " + config.configCode()
                        + ": unsupported provider " + config.provider());
                continue;
            }
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
                    continue;
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
            count++;
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
                config.capabilities()
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

        boolean restoreDisabledModelBinding = isDisabledModelConfig(modelConfigId);
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
                restoreDisabledModelBinding ? null : modelConfigId,
                item.executionHandler(),
                null
        );
        AiTool existing = findToolByCode(item.toolCode()).orElse(null);
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

    private boolean isDisabledModelConfig(Long modelConfigId) {
        if (modelConfigId == null) {
            return false;
        }
        AgentModelConfig config = agentModelConfigMapper.findActiveById(modelConfigId);
        return config != null && Boolean.FALSE.equals(config.getEnabled());
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
