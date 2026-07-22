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
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountMigrationService;
import com.aiminilab.aitoolmarket.agent.service.UnifiedApiOverviewService;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.task.routing.ModelRoutingPolicy;
import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.aiminilab.aitoolmarket.task.routing.mapper.AccountModelRouteStateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ModelCapabilityService modelCapabilityService;
    private final AccountModelRouteStateMapper routeStateMapper;
    private final ObjectMapper objectMapper;

    public UnifiedApiOverviewServiceImpl(ModelVendorAccountMigrationService migrationService,
                                         ModelVendorAccountMapper vendorAccountMapper,
                                         AgentModelConfigMapper agentModelConfigMapper,
                                         VendorCodeResolver vendorCodeResolver,
                                         ModelProviderRegistry providerRegistry,
                                         ModelCapabilitiesCodec capabilitiesCodec,
                                         ModelCapabilityService modelCapabilityService,
                                         AccountModelRouteStateMapper routeStateMapper,
                                         ObjectMapper objectMapper) {
        this.migrationService = migrationService;
        this.vendorAccountMapper = vendorAccountMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.vendorCodeResolver = vendorCodeResolver;
        this.providerRegistry = providerRegistry;
        this.capabilitiesCodec = capabilitiesCodec;
        this.modelCapabilityService = modelCapabilityService;
        this.routeStateMapper = routeStateMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public UnifiedApiOverviewResponse overview() {
        migrationService.migrateIfNeeded();

        List<ModelVendorAccount> accounts = vendorAccountMapper.findAllActive();
        List<AgentModelConfig> configs = agentModelConfigMapper.findAllActive();
        Map<Long, AccountModelRouteState> routeStateByModelId = loadRouteStates(configs);

        Map<Long, ModelVendorAccount> accountById = accounts.stream()
                .collect(Collectors.toMap(ModelVendorAccount::getId, account -> account, (a, b) -> a));

        Map<String, List<ModelVendorAccountResponse>> accountsByVendor = new LinkedHashMap<>();
        for (ModelVendorAccount account : accounts) {
            String vendorCode = vendorCodeResolver.canonicalVendorCode(
                    vendorCodeResolver.resolveEffectiveVendorCode(account));
            accountsByVendor.computeIfAbsent(vendorCode, key -> new ArrayList<>())
                    .add(ModelVendorAccountResponse.from(
                            account,
                            vendorCodeResolver.vendorLabel(vendorCode),
                            vendorAccountMapper.countActiveModelsByAccountId(account.getId())));
        }

        Map<String, List<UnifiedApiModelItemResponse>> modelsByVendor = new LinkedHashMap<>();
        for (AgentModelConfig config : configs) {
            String vendorCode = vendorCodeResolver.canonicalVendorCode(resolveConfigVendorCode(config, accountById));
            String accountName = null;
            String accountHealthStatus = null;
            if (config.getVendorAccountId() != null) {
                ModelVendorAccount account = accountById.get(config.getVendorAccountId());
                if (account != null) {
                    accountName = account.getAccountName();
                    accountHealthStatus = account.getHealthStatus();
                    if (Objects.equals(config.getRoutingPoolId(), account.getRoutingPoolId())) {
                        config.setRoutingPoolName(account.getRoutingPoolName());
                    }
                }
            }
            modelsByVendor.computeIfAbsent(vendorCode, key -> new ArrayList<>())
                    .add(UnifiedApiModelItemResponse.from(
                            config,
                            accountName,
                            capabilitiesCodec,
                            accountHealthStatus,
                            routingExclusionReason(
                                    config, vendorCode, configs, accountById, routeStateByModelId)
                    ));
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
                        providersForVendor(vendorCode),
                        sortAccounts(accountsByVendor.getOrDefault(vendorCode, List.of())),
                        sortModels(modelsByVendor.getOrDefault(vendorCode, List.of()))
                ))
                .toList();

        List<UnifiedApiUnconfiguredVendorResponse> unconfigured = vendorCodeResolver.vendorCatalog().entrySet().stream()
                .filter(entry -> !"openai_gateway".equals(entry.getKey()))
                .filter(entry -> !"infinite_talk".equalsIgnoreCase(entry.getKey()))
                .filter(entry -> !"infinitetalk".equalsIgnoreCase(entry.getKey()))
                .filter(entry -> !configuredVendors.contains(vendorCodeResolver.canonicalVendorCode(entry.getKey())))
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
                .filter(UnifiedApiOverviewServiceImpl::isNegativeBalance)
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

    static boolean isNegativeBalance(ModelVendorAccount account) {
        return account.getBalanceAmount() != null && account.getBalanceAmount().signum() < 0;
    }

    private String resolveConfigVendorCode(AgentModelConfig config, Map<Long, ModelVendorAccount> accountById) {
        if (config.getVendorAccountId() != null) {
            ModelVendorAccount account = accountById.get(config.getVendorAccountId());
            if (account != null && account.getVendorCode() != null) {
                return vendorCodeResolver.resolveEffectiveVendorCode(account);
            }
        }
        String inferred = vendorCodeResolver.resolveVendorCode(config);
        if (inferred != null && !inferred.isBlank() && !"other".equals(inferred)
                && !"openai".equals(inferred) && !"openai_gateway".equals(inferred)) {
            return inferred;
        }
        return inferred;
    }

    private String routingExclusionReason(AgentModelConfig reference,
                                          String vendorCode,
                                          List<AgentModelConfig> configs,
                                          Map<Long, ModelVendorAccount> accountById,
                                          Map<Long, AccountModelRouteState> routeStateByModelId) {
        if (reference.getRoutingPoolId() == null) {
            return null;
        }
        List<ModelVendorAccount> poolMembers = accountById.values().stream()
                .filter(account -> Objects.equals(
                        account.getRoutingPoolId(), reference.getRoutingPoolId()))
                .filter(account -> vendorCode.equals(vendorCodeResolver.canonicalVendorCode(
                        vendorCodeResolver.resolveEffectiveVendorCode(account))))
                .toList();
        if (poolMembers.isEmpty()) {
            return "ROUTING_POOL_EMPTY";
        }
        ModelVendorAccount source = accountById.get(reference.getVendorAccountId());
        if (source == null) {
            return "ACCOUNT_UNBOUND";
        }
        if (!Objects.equals(source.getRoutingPoolId(), reference.getRoutingPoolId())) {
            return "ACCOUNT_POOL_MISMATCH";
        }
        if (!Boolean.TRUE.equals(reference.getEnabled())) {
            return "MODEL_DISABLED";
        }

        Set<Long> eligibleAccountIds = poolMembers.stream()
                .filter(account -> Boolean.TRUE.equals(account.getEnabled()))
                .filter(account -> Boolean.TRUE.equals(account.getLoadBalanceEnabled()))
                .map(ModelVendorAccount::getId)
                .collect(Collectors.toSet());
        if (eligibleAccountIds.isEmpty()) {
            return "NO_ELIGIBLE_POOL_ACCOUNT";
        }

        List<AgentModelConfig> sameRoute = configs.stream()
                .filter(candidate -> candidate.getVendorAccountId() != null)
                .filter(candidate -> eligibleAccountIds.contains(candidate.getVendorAccountId()))
                .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
                .filter(candidate -> equalsIgnoreCase(reference.getProvider(), candidate.getProvider()))
                .filter(candidate -> java.util.Objects.equals(reference.getModelName(), candidate.getModelName()))
                .toList();
        if (sameRoute.isEmpty()) {
            return "NO_COMPATIBLE_MODEL";
        }

        boolean priceMismatch = false;
        List<AgentModelConfig> compatible = new ArrayList<>();
        for (AgentModelConfig candidate : sameRoute) {
            String reason = ModelRoutingPolicy.incompatibilityReason(
                    reference, candidate, modelCapabilityService, objectMapper);
            if (reason == null) {
                compatible.add(candidate);
            } else {
                priceMismatch = priceMismatch || "PRICE_MISMATCH".equals(reason);
            }
        }
        if (compatible.isEmpty()) {
            return priceMismatch ? "PRICE_MISMATCH" : "ROUTE_CONFIG_MISMATCH";
        }
        List<AgentModelConfig> uniqueCandidates = ModelRoutingPolicy.deduplicateByAccount(compatible);
        LocalDateTime now = LocalDateTime.now();
        boolean allCircuitBlocked = uniqueCandidates.stream().allMatch(candidate ->
                !ModelRoutingPolicy.circuitAllowsSelection(
                        routeStateByModelId.get(candidate.getId()), now));
        return allCircuitBlocked ? "CIRCUIT_OPEN" : null;
    }

    private Map<Long, AccountModelRouteState> loadRouteStates(List<AgentModelConfig> configs) {
        List<Long> modelIds = configs.stream()
                .map(AgentModelConfig::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        List<AccountModelRouteState> states = routeStateMapper.selectList(
                new LambdaQueryWrapper<AccountModelRouteState>()
                        .in(AccountModelRouteState::getModelConfigId, modelIds));
        if (states == null || states.isEmpty()) {
            return Map.of();
        }
        return states.stream().collect(Collectors.toMap(
                AccountModelRouteState::getModelConfigId,
                state -> state,
                (left, right) -> left
        ));
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
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
