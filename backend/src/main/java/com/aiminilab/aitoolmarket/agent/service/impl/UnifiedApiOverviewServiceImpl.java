package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountResponse;
import com.aiminilab.aitoolmarket.agent.dto.UnifiedApiModelItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.UnifiedApiOverviewResponse;
import com.aiminilab.aitoolmarket.agent.dto.UnifiedApiSummaryResponse;
import com.aiminilab.aitoolmarket.agent.dto.UnifiedApiUnconfiguredVendorResponse;
import com.aiminilab.aitoolmarket.agent.dto.UnifiedApiVendorGroupResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountMigrationService;
import com.aiminilab.aitoolmarket.agent.service.UnifiedApiOverviewService;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UnifiedApiOverviewServiceImpl implements UnifiedApiOverviewService {

    private final ModelVendorAccountMigrationService migrationService;
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final VendorCodeResolver vendorCodeResolver;
    private final ModelProviderRegistry providerRegistry;
    private final ModelCapabilitiesCodec capabilitiesCodec;

    public UnifiedApiOverviewServiceImpl(ModelVendorAccountMigrationService migrationService,
                                         ModelVendorAccountMapper vendorAccountMapper,
                                         AgentModelConfigMapper agentModelConfigMapper,
                                         VendorCodeResolver vendorCodeResolver,
                                         ModelProviderRegistry providerRegistry,
                                         ModelCapabilitiesCodec capabilitiesCodec) {
        this.migrationService = migrationService;
        this.vendorAccountMapper = vendorAccountMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.vendorCodeResolver = vendorCodeResolver;
        this.providerRegistry = providerRegistry;
        this.capabilitiesCodec = capabilitiesCodec;
    }

    @Override
    public UnifiedApiOverviewResponse overview() {
        migrationService.migrateIfNeeded();

        List<ModelVendorAccount> accounts = vendorAccountMapper.findAllActive();
        List<AgentModelConfig> configs = agentModelConfigMapper.findAllActive();

        Map<Long, ModelVendorAccount> accountById = accounts.stream()
                .collect(Collectors.toMap(ModelVendorAccount::getId, account -> account, (a, b) -> a));

        Map<String, List<ModelVendorAccountResponse>> accountsByVendor = new LinkedHashMap<>();
        for (ModelVendorAccount account : accounts) {
            String vendorCode = canonicalVendorCode(resolveAccountVendorCode(account));
            accountsByVendor.computeIfAbsent(vendorCode, key -> new ArrayList<>())
                    .add(ModelVendorAccountResponse.from(
                            account,
                            vendorCodeResolver.vendorLabel(vendorCode),
                            vendorAccountMapper.countActiveModelsByAccountId(account.getId())));
        }

        Map<String, List<UnifiedApiModelItemResponse>> modelsByVendor = new LinkedHashMap<>();
        for (AgentModelConfig config : configs) {
            String vendorCode = canonicalVendorCode(resolveConfigVendorCode(config, accountById));
            String accountName = null;
            String accountHealthStatus = null;
            if (config.getVendorAccountId() != null) {
                ModelVendorAccount account = accountById.get(config.getVendorAccountId());
                if (account != null) {
                    accountName = account.getAccountName();
                    accountHealthStatus = account.getHealthStatus();
                }
            }
            modelsByVendor.computeIfAbsent(vendorCode, key -> new ArrayList<>())
                    .add(UnifiedApiModelItemResponse.from(config, accountName, capabilitiesCodec, accountHealthStatus));
        }

        Set<String> configuredVendors = new HashSet<>();
        configuredVendors.addAll(accountsByVendor.keySet());
        configuredVendors.addAll(modelsByVendor.keySet());

        List<UnifiedApiVendorGroupResponse> vendorGroups = configuredVendors.stream()
                .sorted(Comparator.comparing(vendorCodeResolver::vendorLabel))
                .map(vendorCode -> new UnifiedApiVendorGroupResponse(
                        vendorCode,
                        vendorCodeResolver.vendorLabel(vendorCode),
                        vendorCodeResolver.vendorIconAsset(vendorCode),
                        sortAccounts(accountsByVendor.getOrDefault(vendorCode, List.of())),
                        sortModels(modelsByVendor.getOrDefault(vendorCode, List.of()))
                ))
                .toList();

        List<UnifiedApiUnconfiguredVendorResponse> unconfigured = vendorCodeResolver.vendorCatalog().entrySet().stream()
                .filter(entry -> !"openai_gateway".equals(entry.getKey()))
                .filter(entry -> !"infinite_talk".equalsIgnoreCase(entry.getKey()))
                .filter(entry -> !"infinitetalk".equalsIgnoreCase(entry.getKey()))
                .filter(entry -> !configuredVendors.contains(canonicalVendorCode(entry.getKey())))
                .filter(entry -> !"mock".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByValue())
                .map(entry -> new UnifiedApiUnconfiguredVendorResponse(
                        entry.getKey(),
                        entry.getValue(),
                        vendorCodeResolver.vendorIconAsset(entry.getKey()),
                        providersForVendor(entry.getKey())
                ))
                .toList();

        int lowBalance = (int) accounts.stream()
                .filter(account -> "LOW".equalsIgnoreCase(account.getBalanceStatus())
                        || "SUSPECTED_INSUFFICIENT".equalsIgnoreCase(account.getBalanceStatus()))
                .count();
        int unhealthy = (int) accounts.stream()
                .filter(account -> "ERROR".equalsIgnoreCase(account.getHealthStatus()))
                .count();
        int enabledModels = (int) configs.stream().filter(config -> Boolean.TRUE.equals(config.getEnabled())).count();

        UnifiedApiSummaryResponse summary = new UnifiedApiSummaryResponse(
                vendorGroups.size() + unconfigured.size(),
                accounts.size(),
                configs.size(),
                enabledModels,
                lowBalance,
                unhealthy
        );

        return new UnifiedApiOverviewResponse(summary, vendorGroups, unconfigured);
    }

    private String resolveConfigVendorCode(AgentModelConfig config, Map<Long, ModelVendorAccount> accountById) {
        if (config.getVendorAccountId() != null) {
            ModelVendorAccount account = accountById.get(config.getVendorAccountId());
            if (account != null && account.getVendorCode() != null) {
                return resolveAccountVendorCode(account);
            }
        }
        String inferred = vendorCodeResolver.resolveVendorCode(config);
        if (inferred != null && !inferred.isBlank() && !"other".equals(inferred)
                && !"openai".equals(inferred) && !"openai_gateway".equals(inferred)) {
            return inferred;
        }
        return inferred;
    }

    private String resolveAccountVendorCode(ModelVendorAccount account) {
        String declared = account.getVendorCode() == null ? "" : account.getVendorCode().trim();
        String inferred = vendorCodeResolver.resolveVendorCode(
                "openai_compatible",
                account.getBaseUrl(),
                account.getAccountName(),
                null
        );
        if ("openai".equalsIgnoreCase(inferred)) {
            return "openai";
        }
        if (!inferred.isBlank() && !"openai".equalsIgnoreCase(inferred) && !"other".equalsIgnoreCase(inferred)) {
            return inferred;
        }
        return declared.isBlank() ? inferred : declared;
    }

    private static String canonicalVendorCode(String vendorCode) {
        if ("openai_gateway".equalsIgnoreCase(vendorCode)) {
            return "openai";
        }
        if ("suno_music".equalsIgnoreCase(vendorCode)) {
            return "suno";
        }
        return vendorCode;
    }

    private List<String> providersForVendor(String vendorCode) {
        return providerRegistry.listAll().stream()
                .filter(definition -> vendorCode.equals(vendorCodeResolver.resolveVendorCode(
                        definition.code(),
                        definition.defaultBaseUrl(),
                        definition.label(),
                        definition.defaultModel())))
                .map(ModelProviderDefinition::code)
                .sorted()
                .toList();
    }

    private static List<ModelVendorAccountResponse> sortAccounts(List<ModelVendorAccountResponse> accounts) {
        return accounts.stream()
                .sorted(Comparator
                        .comparingInt(ModelVendorAccountResponse::modelCount).reversed()
                        .thenComparing(account -> account.apiKeyMasked() == null || account.apiKeyMasked().isBlank())
                        .thenComparing(ModelVendorAccountResponse::id))
                .toList();
    }

    private static List<UnifiedApiModelItemResponse> sortModels(List<UnifiedApiModelItemResponse> models) {
        return models.stream()
                .sorted(Comparator
                        .comparing((UnifiedApiModelItemResponse model) -> !Boolean.TRUE.equals(model.isDefault()))
                        .thenComparing(model -> model.displayName() == null ? "" : model.displayName()))
                .toList();
    }

    // iconAsset moved to VendorCodeResolver (DB-first, with compatibility fallbacks).
}
