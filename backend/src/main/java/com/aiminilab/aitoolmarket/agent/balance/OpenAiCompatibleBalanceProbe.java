package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;

/**
 * 尝试常见 OpenAI 兼容网关上的余额/用户信息接口（/v1/user/info、/user/balance 等）。
 */
final class OpenAiCompatibleBalanceProbe {

    private static final List<String> PROBE_PATHS = List.of(
            "/v1/user/info",
            "/user/info",
            "/user/balance",
            "/v1/billing/credit_grants"
    );

    private final ObjectMapper objectMapper;

    OpenAiCompatibleBalanceProbe(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    BalanceQueryResult probe(ModelVendorAccount account, String defaultOrigin) {
        try {
            String apiKey = VendorBalanceHttpSupport.requireApiKey(account);
            String origin = VendorBalanceHttpSupport.normalizeApiOrigin(account.getBaseUrl(), defaultOrigin);
            RestClientResponseException lastHttpError = null;
            for (String path : PROBE_PATHS) {
                try {
                    String body = VendorBalanceHttpSupport.getJson(origin + path, apiKey);
                    BalanceQueryResult parsed = parseBody(body, defaultCurrency(account));
                    if (parsed != null) {
                        return parsed;
                    }
                } catch (RestClientResponseException exception) {
                    int status = exception.getStatusCode().value();
                    if (status == 404 || status == 405 || status == 501) {
                        lastHttpError = exception;
                        continue;
                    }
                    return VendorBalanceHttpSupport.httpFailure(exception);
                }
            }
            if (lastHttpError != null) {
                return BalanceQueryResult.unsupported(
                        "中转站未提供标准余额 JSON 接口（HTTP " + lastHttpError.getStatusCode().value() + "），请在控制台查看或手动填写余额。");
            }
            return BalanceQueryResult.unsupported("中转站未提供标准余额 JSON 接口，请在控制台查看或手动填写余额。");
        } catch (RestClientResponseException exception) {
            return VendorBalanceHttpSupport.httpFailure(exception);
        } catch (Exception exception) {
            return BalanceQueryResult.unsupported("中转站未提供标准余额 JSON 接口，请在控制台查看或手动填写余额。");
        }
    }

    BalanceQueryResult parseBody(String body) throws Exception {
        return parseBody(body, "CNY");
    }

    BalanceQueryResult parseBody(String body, String defaultCurrency) throws Exception {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isBlank() || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(trimmed);
        } catch (JsonProcessingException exception) {
            return null;
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode() && !data.isNull() && data.isObject()) {
            BalanceQueryResult fromData = parseNode(data, defaultCurrency);
            if (fromData != null) {
                return fromData;
            }
        }
        BalanceQueryResult fromRoot = parseNode(root, defaultCurrency);
        if (fromRoot != null) {
            return fromRoot;
        }
        if (root.path("balance_infos").isArray() && !root.path("balance_infos").isEmpty()) {
            return DeepSeekBalanceAdapter.parseBody(objectMapper, body);
        }
        return null;
    }

    private BalanceQueryResult parseNode(JsonNode node, String defaultCurrency) {
        BigDecimal total = firstDecimal(node,
                "totalBalance", "balance", "chargeBalance", "available_balance", "availableBalance",
                "total_balance", "cashBalance", "CashBalance", "quota", "available_quota", "availableQuota");
        if (total != null) {
            String currency = text(node, "currency", defaultCurrency);
            return BalanceQueryResult.ok(total, currency);
        }
        JsonNode grants = node.path("grants");
        if (grants.isObject()) {
            BigDecimal available = firstDecimal(grants, "available", "balance");
            if (available != null) {
                return BalanceQueryResult.ok(available, "USD");
            }
        }
        return null;
    }

    private static String defaultCurrency(ModelVendorAccount account) {
        String baseUrl = account == null || account.getBaseUrl() == null ? "" : account.getBaseUrl().trim().toLowerCase();
        String vendorCode = account == null || account.getVendorCode() == null ? "" : account.getVendorCode().trim().toLowerCase();
        if (baseUrl.contains("ofox.ai") || ("openai".equals(vendorCode) && baseUrl.contains("openai"))) {
            return "USD";
        }
        return "CNY";
    }

    private static BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                BigDecimal parsed = VendorBalanceHttpSupport.parseDecimal(value.asText());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? fallback : text;
    }
}
