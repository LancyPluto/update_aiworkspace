package com.aiminilab.aitoolmarket.agent.balance;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

@Component
public class SiliconFlowBalanceAdapter implements VendorBalanceAdapter {

    private static final String DEFAULT_ORIGIN_CN = "https://api.siliconflow.cn";

    private final ObjectMapper objectMapper;

    public SiliconFlowBalanceAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String vendorCode) {
        return "siliconflow".equalsIgnoreCase(vendorCode);
    }

    @Override
    public BalanceQueryResult query(ModelVendorAccount account) {
        try {
            String apiKey = VendorBalanceHttpSupport.requireApiKey(account);
            String origin = resolveOrigin(account.getBaseUrl());
            String body = VendorBalanceHttpSupport.getJson(origin + "/v1/user/info", apiKey);
            return parseBody(body);
        } catch (RestClientResponseException exception) {
            return VendorBalanceHttpSupport.httpFailure(exception);
        } catch (Exception exception) {
            return VendorBalanceHttpSupport.httpFailure(exception);
        }
    }

    private String resolveOrigin(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_ORIGIN_CN;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed.substring(0, trimmed.length() - 3);
        }
        if (trimmed.contains("siliconflow")) {
            int idx = trimmed.indexOf("://");
            if (idx > 0) {
                int pathIdx = trimmed.indexOf('/', idx + 3);
                return pathIdx > 0 ? trimmed.substring(0, pathIdx) : trimmed;
            }
        }
        return DEFAULT_ORIGIN_CN;
    }

    static BalanceQueryResult parseBody(String body) throws Exception {
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            int code = root.path("code").asInt(-1);
            if (code > 0 && code != 20000) {
                return BalanceQueryResult.failed("SiliconFlow: " + root.path("message").asText("查询失败"));
            }
            data = root;
        }
        BigDecimal total = firstDecimal(data, "totalBalance", "balance", "chargeBalance");
        if (total == null) {
            return BalanceQueryResult.failed("SiliconFlow 余额字段为空");
        }
        return BalanceQueryResult.ok(total, "CNY");
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
}
