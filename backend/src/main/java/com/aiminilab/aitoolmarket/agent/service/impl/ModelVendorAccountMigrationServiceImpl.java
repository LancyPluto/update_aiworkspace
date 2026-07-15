package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountMigrationService;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ModelVendorAccountMigrationServiceImpl implements ModelVendorAccountMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ModelVendorAccountMigrationServiceImpl.class);

    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final VendorCodeResolver vendorCodeResolver;

    public ModelVendorAccountMigrationServiceImpl(AgentModelConfigMapper agentModelConfigMapper,
                                                  ModelVendorAccountMapper vendorAccountMapper,
                                                  VendorCodeResolver vendorCodeResolver) {
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.vendorAccountMapper = vendorAccountMapper;
        this.vendorCodeResolver = vendorCodeResolver;
    }

    @Override
    @Transactional
    public void migrateIfNeeded() {
        List<AgentModelConfig> configs = agentModelConfigMapper.findAllActive();
        boolean anyMissing = configs.stream().anyMatch(config -> config.getVendorAccountId() == null);
        if (anyMissing) {
            Map<String, Long> accountByGroupKey = new HashMap<>();
            LocalDateTime now = LocalDateTime.now();
            int linked = 0;
            for (AgentModelConfig config : configs) {
                if (config.getVendorAccountId() != null) {
                    continue;
                }
                String vendorCode = vendorCodeResolver.resolveVendorCode(config);
                String groupKey = buildGroupKey(vendorCode, config);
                Long accountId = findExistingAccountId(vendorCode, config, accountByGroupKey);
                if (accountId == null) {
                    ModelVendorAccount account = newAccountFromConfig(config, vendorCode, now);
                    vendorAccountMapper.insertAccount(account);
                    accountId = account.getId();
                    accountByGroupKey.put(groupKey, accountId);
                } else {
                    accountByGroupKey.putIfAbsent(groupKey, accountId);
                }
                config.setVendorAccountId(accountId);
                agentModelConfigMapper.updateVendorAccountId(config.getId(), accountId, now);
                linked++;
            }
            if (linked > 0) {
                log.info("Model vendor account migration linked {} model configs to {} accounts",
                        linked, accountByGroupKey.size());
            }
        }
        upgradeBalanceQueryModes();
        downgradeOpenAiRestBalanceModes();
        downgradeUnsupportedRestBalanceModes();
    }

    private void downgradeOpenAiRestBalanceModes() {
        for (ModelVendorAccount account : vendorAccountMapper.findAllActive()) {
            String mode = account.getBalanceQueryMode() == null ? "" : account.getBalanceQueryMode().trim().toUpperCase(Locale.ROOT);
            if (!"REST_API".equals(mode) && !"INFERRED".equals(mode)) {
                continue;
            }
            String vendor = account.getVendorCode() == null ? "" : account.getVendorCode().trim().toLowerCase(Locale.ROOT);
            if (!"openai".equals(vendor) && !"openai_gateway".equals(vendor)) {
                continue;
            }
            account.setBalanceQueryMode("MANUAL");
            account.setBalanceErrorMessage(null);
            account.setBalanceStatus(balanceStatus(account));
            account.setUpdatedAt(LocalDateTime.now());
            vendorAccountMapper.updateAccount(account);
            log.info("Adjusted OpenAI-compatible vendor account id={} balance_query_mode MANUAL", account.getId());
        }
    }

    /**
     * 火山方舟 / 可灵等无公开余额 API 的厂商，历史上误设为 REST_API 会导致刷新后一直「未知」。
     */
    private void downgradeUnsupportedRestBalanceModes() {
        for (ModelVendorAccount account : vendorAccountMapper.findAllActive()) {
            if (!"REST_API".equalsIgnoreCase(account.getBalanceQueryMode())) {
                continue;
            }
            String vendor = account.getVendorCode() == null ? "" : account.getVendorCode().trim().toLowerCase(Locale.ROOT);
            if (!"volcengine".equals(vendor) && !"kling".equals(vendor) && !"minimax".equals(vendor)) {
                continue;
            }
            if (account.getBalanceAmount() != null) {
                continue;
            }
            account.setBalanceQueryMode("NONE");
            account.setBalanceStatus("UNKNOWN");
            account.setBalanceErrorMessage(switch (vendor) {
                case "volcengine" -> "火山方舟无公开余额 API，请使用控制台外链或改用手填余额";
                case "kling" -> "可灵无公开余额 API，请使用控制台外链或改用手填余额";
                case "minimax" -> "MiniMax 无稳定 OpenAI 风格余额 API，请使用控制台外链或改用手填余额";
                default -> "该厂商不支持自动余额查询";
            });
            account.setUpdatedAt(LocalDateTime.now());
            vendorAccountMapper.updateAccount(account);
            log.info("Adjusted balance_query_mode NONE for vendor account id={} vendor={}", account.getId(), vendor);
        }
    }

    /**
     * 迁移时可能按「每个模型」生成了多个同厂商账户（账户名甚至用了模型显示名）。
     * 将 API Key / Base URL 兼容的账户合并为一个，避免运营误以为要分别配置密钥。
     */
    private void consolidateDuplicateAccounts() {
        Map<String, List<ModelVendorAccount>> byVendor = new LinkedHashMap<>();
        for (ModelVendorAccount account : vendorAccountMapper.findAllActive()) {
            String vendor = account.getVendorCode() == null ? "other" : account.getVendorCode().trim().toLowerCase(Locale.ROOT);
            byVendor.computeIfAbsent(vendor, key -> new ArrayList<>()).add(account);
        }
        int merged = 0;
        for (List<ModelVendorAccount> accounts : byVendor.values()) {
            if (accounts.size() <= 1) {
                continue;
            }
            accounts.sort(Comparator
                    .comparingInt((ModelVendorAccount a) -> vendorAccountMapper.countActiveModelsByAccountId(a.getId()))
                    .reversed()
                    .thenComparing(a -> hasApiKey(a) ? 0 : 1)
                    .thenComparing(ModelVendorAccount::getId));
            ModelVendorAccount canonical = accounts.get(0);
            LocalDateTime now = LocalDateTime.now();
            for (int i = 1; i < accounts.size(); i++) {
                ModelVendorAccount other = accounts.get(i);
                if (!shouldMergeAccounts(canonical, other)) {
                    continue;
                }
                reassignModels(other.getId(), canonical.getId(), now);
                mergeAccountFields(canonical, other);
                vendorAccountMapper.updateAccount(canonical);
                vendorAccountMapper.softDelete(other.getId());
                merged++;
                log.info("Merged duplicate vendor account id={} ({}) into id={} for vendor={}",
                        other.getId(), other.getAccountName(), canonical.getId(), canonical.getVendorCode());
            }
            normalizeDefaultAccountName(canonical);
            vendorAccountMapper.updateAccount(canonical);
        }
        if (merged > 0) {
            log.info("Consolidated {} duplicate model vendor accounts", merged);
        }
    }

    private void reassignModels(Long fromAccountId, Long toAccountId, LocalDateTime now) {
        List<AgentModelConfig> configs = agentModelConfigMapper.findAllActive();
        for (AgentModelConfig config : configs) {
            if (fromAccountId.equals(config.getVendorAccountId())) {
                config.setVendorAccountId(toAccountId);
                agentModelConfigMapper.updateVendorAccountId(config.getId(), toAccountId, now);
            }
        }
    }

    private static void mergeAccountFields(ModelVendorAccount target, ModelVendorAccount source) {
        if (!hasApiKey(target) && hasApiKey(source)) {
            target.setApiKey(source.getApiKey());
        }
        if (isBlank(target.getBaseUrl()) && !isBlank(source.getBaseUrl())) {
            target.setBaseUrl(source.getBaseUrl());
        }
        if (isBlank(target.getExtraAuthJson()) && !isBlank(source.getExtraAuthJson())) {
            target.setExtraAuthJson(source.getExtraAuthJson());
        }
        if (isBlank(target.getConsoleUrl()) && !isBlank(source.getConsoleUrl())) {
            target.setConsoleUrl(source.getConsoleUrl());
        }
        if (isBlank(target.getBalanceUrl()) && !isBlank(source.getBalanceUrl())) {
            target.setBalanceUrl(source.getBalanceUrl());
        }
        if (target.getBalanceAmount() == null && source.getBalanceAmount() != null) {
            target.setBalanceAmount(source.getBalanceAmount());
            target.setBalanceCurrency(source.getBalanceCurrency());
            target.setBalanceStatus(source.getBalanceStatus());
            target.setBalanceUpdatedAt(source.getBalanceUpdatedAt());
            target.setBalanceErrorMessage(source.getBalanceErrorMessage());
        }
        target.setUpdatedAt(LocalDateTime.now());
    }

    private static void normalizeDefaultAccountName(ModelVendorAccount account) {
        String name = account.getAccountName();
        if (name == null || name.isBlank() || looksLikeModelDisplayName(name)) {
            account.setAccountName("默认账户");
            account.setUpdatedAt(LocalDateTime.now());
        }
    }

    private static boolean looksLikeModelDisplayName(String name) {
        String trimmed = name.trim();
        if (trimmed.equals("默认账户")) {
            return false;
        }
        return trimmed.contains(" ") || trimmed.matches(".*[Vv]\\d.*") || trimmed.length() > 24;
    }

    private static boolean shouldMergeAccounts(ModelVendorAccount a, ModelVendorAccount b) {
        return keyCompatible(a.getApiKey(), b.getApiKey()) && baseCompatible(a.getBaseUrl(), b.getBaseUrl());
    }

    private static boolean keyCompatible(String k1, String k2) {
        String a = normalizeKey(k1);
        String b = normalizeKey(k2);
        return a.equals(b) || a.isEmpty() || b.isEmpty();
    }

    private static boolean baseCompatible(String u1, String u2) {
        String a = normalizeUrl(u1);
        String b = normalizeUrl(u2);
        return a.equals(b) || a.isEmpty() || b.isEmpty();
    }

    private static boolean hasApiKey(ModelVendorAccount account) {
        return !normalizeKey(account.getApiKey()).isEmpty();
    }

    private static String normalizeKey(String apiKey) {
        return apiKey == null ? "" : apiKey.trim();
    }

    private static String normalizeUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim().toLowerCase(Locale.ROOT);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Long findExistingAccountId(String vendorCode, AgentModelConfig config, Map<String, Long> accountByGroupKey) {
        String groupKey = buildGroupKey(vendorCode, config);
        Long fromRun = accountByGroupKey.get(groupKey);
        if (fromRun != null) {
            return fromRun;
        }
        String configKey = normalizeKey(config.getApiKey());
        List<ModelVendorAccount> existing = vendorAccountMapper.findActiveByVendorCode(vendorCode);
        for (ModelVendorAccount account : existing) {
            if (keyCompatible(configKey, account.getApiKey()) && baseCompatible(config.getBaseUrl(), account.getBaseUrl())) {
                return account.getId();
            }
        }
        if (configKey.isEmpty()) {
            return existing.stream()
                    .filter(ModelVendorAccountMigrationServiceImpl::hasApiKey)
                    .map(ModelVendorAccount::getId)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private void upgradeBalanceQueryModes() {
        for (ModelVendorAccount account : vendorAccountMapper.findAllActive()) {
            String target = defaultBalanceMode(account.getVendorCode());
            if ("REST_API".equals(target) && !"REST_API".equalsIgnoreCase(account.getBalanceQueryMode())) {
                account.setBalanceQueryMode(target);
                account.setUpdatedAt(LocalDateTime.now());
                vendorAccountMapper.updateAccount(account);
            }
        }
    }

    private static String buildGroupKey(String vendorCode, AgentModelConfig config) {
        String base = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim().toLowerCase(Locale.ROOT);
        String key = config.getApiKey() == null ? "" : config.getApiKey().trim();
        String extra = config.getExtraAuthJson() == null ? "" : config.getExtraAuthJson().trim();
        return vendorCode + "|" + base + "|" + key.hashCode() + "|" + extra.hashCode();
    }

    private static String defaultBalanceMode(String vendorCode) {
        return switch (vendorCode) {
            case "deepseek", "siliconflow" -> "REST_API";
            case "volcengine", "kling", "minimax" -> "NONE";
            default -> "MANUAL";
        };
    }

    private static String balanceStatus(ModelVendorAccount account) {
        if (account.getBalanceAmount() == null) {
            return "UNKNOWN";
        }
        if (account.getBalanceLowThreshold() != null
                && account.getBalanceAmount().compareTo(account.getBalanceLowThreshold()) < 0) {
            return "LOW";
        }
        return "OK";
    }

    private static ModelVendorAccount newAccountFromConfig(AgentModelConfig config, String vendorCode, LocalDateTime now) {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setVendorCode(vendorCode);
        account.setAccountName("默认账户");
        account.setBaseUrl(config.getBaseUrl());
        account.setApiKey(config.getApiKey());
        account.setExtraAuthJson(config.getExtraAuthJson());
        account.setConsoleUrl(config.getConsoleUrl());
        account.setBalanceUrl(config.getBalanceUrl());
        account.setBalanceQueryMode(defaultBalanceMode(vendorCode));
        account.setBalanceCurrency("CNY");
        account.setBalanceStatus("UNKNOWN");
        account.setHealthStatus("UNKNOWN");
        account.setEnabled(true);
        account.setDeleted(false);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return account;
    }
}
