package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Locale;

@Component
public class DeepSeekBalanceAdapter implements VendorBalanceAdapter {

    private static final String DEFAULT_ORIGIN = "https://api.deepseek.com";

    private final ObjectMapper objectMapper;

    public DeepSeekBalanceAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String vendorCode) {
        return "deepseek".equalsIgnoreCase(vendorCode);
    }

    @Override
    public BalanceQueryResult query(ModelVendorAccount account) {
        try {
            String apiKey = VendorBalanceHttpSupport.requireApiKey(account);
            String origin = VendorBalanceHttpSupport.normalizeApiOrigin(account.getBaseUrl(), DEFAULT_ORIGIN);
            String body = VendorBalanceHttpSupport.getJson(origin + "/user/balance", apiKey);
            return parseBody(objectMapper, body);
        } catch (RestClientResponseException exception) {
            return VendorBalanceHttpSupport.httpFailure(exception);
        } catch (Exception exception) {
            return VendorBalanceHttpSupport.httpFailure(exception);
        }
    }

    static BalanceQueryResult parseBody(ObjectMapper objectMapper, String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        boolean available = root.path("is_available").asBoolean(true);
        JsonNode infos = root.path("balance_infos");
        if (!infos.isArray() || infos.isEmpty()) {
            return BalanceQueryResult.failed("DeepSeek 余额响应无 balance_infos");
        }
        JsonNode preferred = pickBalanceInfo(infos);
        String currency = text(preferred, "currency", "CNY");
        BigDecimal total = VendorBalanceHttpSupport.parseDecimal(text(preferred, "total_balance", null));
        if (total == null) {
            return BalanceQueryResult.failed("DeepSeek 余额字段为空");
        }
        BalanceQueryResult result = BalanceQueryResult.ok(total, currency);
        if (!available) {
            return new BalanceQueryResult(true, total, currency, "SUSPECTED_INSUFFICIENT", "账户余额不足（is_available=false）");
        }
        return result;
    }

    private static JsonNode pickBalanceInfo(JsonNode infos) {
        for (JsonNode item : infos) {
            if ("CNY".equalsIgnoreCase(text(item, "currency", ""))) {
                return item;
            }
        }
        return infos.get(0);
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
