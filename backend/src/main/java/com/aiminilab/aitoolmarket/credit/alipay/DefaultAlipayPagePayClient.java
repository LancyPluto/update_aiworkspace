package com.aiminilab.aitoolmarket.credit.alipay;

import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;
import com.aiminilab.aitoolmarket.common.error.PayErrors;
import com.aiminilab.aitoolmarket.common.exception.AppException;
import com.aiminilab.aitoolmarket.common.exception.BizException;
import com.aiminilab.aitoolmarket.common.exception.DependencyException;
import com.aiminilab.aitoolmarket.common.exception.SystemException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.credit.dto.AlipayPayDiagnosticResponse;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class DefaultAlipayPagePayClient implements AlipayPagePayClient {
    private static final DateTimeFormatter EXPIRE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String PERMISSION_PROBE_METHOD = "alipay.trade.query";
    private static final String DIAGNOSTIC_TOOL_URL =
            "https://opensupport.alipay.com/support/diagnostic-tools/0c3f29c0-b276-42e2-ba37-7e8ee57fb4be";

    private final AppProperties.AlipayPage properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public DefaultAlipayPagePayClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.properties = appProperties.getPayment().getAlipayPage();
        this.objectMapper = objectMapper;
    }

    @Override
    public AlipayPagePayResponse createPagePayOrder(AlipayPagePayRequest request) {
        requireEnabled();
        String launchPath = "/api/v1/pay/alipay/page/launch?orderNo=" + url(request.outTradeNo());
        return AlipayPagePayResponse.pageRedirect(launchPath);
    }

    @Override
    public AlipayTradeQueryResult queryOrder(String orderNo) {
        requireEnabled();
        String operation = "order-query";
        try {
            JsonNode root = invokeTradeApi("alipay.trade.query", orderNo);
            JsonNode response = root.has("alipay_trade_query_response")
                    ? root.path("alipay_trade_query_response")
                    : root.path("error_response");
            if ("10000".equals(response.path("code").asText())) {
                String amount = response.path("total_amount").asText("");
                return new AlipayTradeQueryResult(
                        response.path("out_trade_no").asText(orderNo),
                        textOrNull(response, "trade_no"),
                        response.path("trade_status").asText("UNKNOWN"),
                        amount.isBlank() ? null : new BigDecimal(amount)
                );
            }
            if ("ACQ.TRADE_NOT_EXIST".equals(response.path("sub_code").asText())) {
                return new AlipayTradeQueryResult(orderNo, null, "NOT_FOUND", null);
            }
            throw providerApiFailure(operation, response);
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidProviderResponse(operation, exception);
        }
    }

    @Override
    public boolean closeOrder(String orderNo) {
        requireEnabled();
        String operation = "order-close";
        try {
            JsonNode root = invokeTradeApi("alipay.trade.close", orderNo);
            JsonNode response = root.has("alipay_trade_close_response")
                    ? root.path("alipay_trade_close_response")
                    : root.path("error_response");
            return "10000".equals(response.path("code").asText())
                    || "ACQ.TRADE_HAS_CLOSE".equals(response.path("sub_code").asText());
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidProviderResponse(operation, exception);
        }
    }

    @Override
    public String buildPagePayHtml(AlipayPagePayRequest request) {
        requireEnabled();
        try {
            return buildAutoSubmitForm(request);
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw configurationFailure("page-pay-form-build", exception);
        }
    }

    private String buildAutoSubmitForm(AlipayPagePayRequest request) throws Exception {
        Map<String, String> params = buildSignedParams(request);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>跳转支付宝</title></head><body>");
        html.append("<form id=\"alipaySubmit\" name=\"alipaySubmit\" action=\"")
                .append(escapeHtml(properties.getGatewayUrl()))
                .append("\" method=\"POST\">");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            html.append("<input type=\"hidden\" name=\"")
                    .append(escapeHtml(entry.getKey()))
                    .append("\" value=\"")
                    .append(escapeHtml(entry.getValue()))
                    .append("\"/>");
        }
        html.append("</form><script>document.getElementById('alipaySubmit').submit();</script></body></html>");
        return html.toString();
    }

    private Map<String, String> buildSignedParams(AlipayPagePayRequest request) throws Exception {
        Map<String, String> bizContent = new TreeMap<>();
        bizContent.put("out_trade_no", request.outTradeNo());
        bizContent.put("total_amount", request.totalAmount().setScale(2).toPlainString());
        bizContent.put("subject", request.subject());
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        if (request.expiresAt() != null) {
            bizContent.put("time_expire", EXPIRE_FORMAT.format(request.expiresAt()));
        }
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", properties.getAppId());
        params.put("method", "alipay.trade.page.pay");
        params.put("format", "JSON");
        params.put("charset", "UTF-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", EXPIRE_FORMAT.format(java.time.LocalDateTime.now()));
        params.put("version", "1.0");
        params.put("notify_url", properties.getNotifyUrl());
        if (StringUtils.hasText(properties.getReturnUrl())) {
            params.put("return_url", properties.getReturnUrl());
        }
        params.put("biz_content", objectMapper.writeValueAsString(bizContent));
        params.put("sign", sign(canonicalPayload(params), privateKey()));
        return params;
    }

    @Override
    public AlipayPayDiagnosticResponse buildDiagnostic(AlipayPagePayRequest request) {
        requireEnabled();
        try {
            Map<String, String> pageParams = buildSignedParams(request);
            JsonNode biz = objectMapper.readTree(pageParams.get("biz_content"));
            JsonNode probe = probePermission(request.outTradeNo());
            JsonNode error = probe.path("error_response");
            JsonNode query = probe.path("alipay_trade_query_response");
            JsonNode source = !error.isMissingNode() && !error.isNull() ? error : query;
            String traceId = textOrNull(source, "trace_id");
            if (traceId == null) {
                traceId = extractTraceIdFromMessage(textOrNull(source, "sub_msg"));
            }
            return new AlipayPayDiagnosticResponse(
                    properties.getAppId(),
                    pageParams.get("method"),
                    request.outTradeNo(),
                    textOrNull(biz, "product_code"),
                    textOrNull(biz, "total_amount"),
                    properties.getNotifyUrl(),
                    properties.getReturnUrl(),
                    properties.getGatewayUrl(),
                    pageParams.get("biz_content"),
                    PERMISSION_PROBE_METHOD,
                    traceId,
                    textOrNull(source, "code"),
                    textOrNull(source, "sub_code"),
                    textOrNull(source, "sub_msg"),
                    DIAGNOSTIC_TOOL_URL,
                    Map.copyOf(pageParams)
            );
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw configurationFailure("diagnostic-build", exception);
        }
    }

    private JsonNode probePermission(String outTradeNo) throws Exception {
        return invokeTradeApi(PERMISSION_PROBE_METHOD, outTradeNo);
    }

    private JsonNode invokeTradeApi(String method, String outTradeNo) {
        Map<String, String> bizContent = new TreeMap<>();
        bizContent.put("out_trade_no", outTradeNo);
        Map<String, String> params = new TreeMap<>();
        try {
            params.put("app_id", properties.getAppId());
            params.put("method", method);
            params.put("format", "JSON");
            params.put("charset", "UTF-8");
            params.put("sign_type", "RSA2");
            params.put("timestamp", EXPIRE_FORMAT.format(java.time.LocalDateTime.now()));
            params.put("version", "1.0");
            params.put("biz_content", objectMapper.writeValueAsString(bizContent));
            params.put("sign", sign(canonicalPayload(params), privateKey()));
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw configurationFailure(method + "-request-build", exception);
        }

        String responseBody = postForm(properties.getGatewayUrl(), encode(params), method);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String expectedResponseField = method.replace('.', '_') + "_response";
            String responseField = root.has(expectedResponseField) ? expectedResponseField : "error_response";
            String signedPayload = extractSignedResponsePayload(responseBody, responseField, method);
            verifyGatewayResponseSignature(root, signedPayload, method);
            return root;
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidProviderResponse(method, exception);
        }
    }

    @Override
    public AlipayNotification parseNotification(Map<String, String> params) {
        requireEnabled();
        try {
            if (!verify(params)) {
                throw invalidCallback("notification-signature", null);
            }
            return new AlipayNotification(
                    params.get("app_id"),
                    params.get("out_trade_no"),
                    params.get("trade_no"),
                    params.get("trade_status"),
                    new BigDecimal(params.getOrDefault("total_amount", "0"))
            );
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCallback("notification-fields", exception);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()
                || !StringUtils.hasText(properties.getAppId())
                || !StringUtils.hasText(properties.getMerchantPrivateKey())
                || !StringUtils.hasText(properties.getAlipayPublicKey())
                || !StringUtils.hasText(properties.getNotifyUrl())) {
            throw configurationFailure("required-configuration", null);
        }
    }

    private boolean verify(Map<String, String> params) {
        String sign = params.get("sign");
        if (!StringUtils.hasText(sign)) {
            return false;
        }
        return verifySignature(canonicalPayload(params), sign);
    }

    private void verifyGatewayResponseSignature(JsonNode root, String payload, String operation) {
        String signature = root.path("sign").asText("");
        if (!StringUtils.hasText(payload) || !StringUtils.hasText(signature)) {
            throw invalidProviderResponse(operation + "-response-signature", null);
        }
        if (!verifySignature(payload, signature)) {
            throw invalidProviderResponse(operation + "-response-signature", null);
        }
    }

    private String extractSignedResponsePayload(String responseBody, String responseField, String operation) {
        try (JsonParser parser = objectMapper.getFactory().createParser(responseBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalidProviderResponse(operation + "-response-payload", null);
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                parser.nextToken();
                if (responseField.equals(fieldName)) {
                    long start = parser.currentTokenLocation().getCharOffset();
                    parser.skipChildren();
                    long end = parser.currentLocation().getCharOffset();
                    if (start >= 0 && end > start && end <= responseBody.length()) {
                        return responseBody.substring((int) start, (int) end);
                    }
                    break;
                }
                parser.skipChildren();
            }
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidProviderResponse(operation + "-response-payload", exception);
        }
        throw invalidProviderResponse(operation + "-response-payload", null);
    }

    private boolean verifySignature(String payload, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey());
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception exception) {
            return false;
        }
    }

    private String sign(String payload, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private PrivateKey privateKey() throws Exception {
        byte[] decoded = Base64.getDecoder().decode(stripPem(properties.getMerchantPrivateKey()));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private PublicKey publicKey() throws Exception {
        byte[] decoded = Base64.getDecoder().decode(stripPem(properties.getAlipayPublicKey()));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    private String extractTraceIdFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        int marker = message.indexOf("traceId=");
        if (marker < 0) {
            return null;
        }
        String tail = message.substring(marker + "traceId=".length());
        int end = tail.indexOf('&');
        return end < 0 ? tail.trim() : tail.substring(0, end).trim();
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> url(entry.getKey()) + "=" + url(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String postForm(String gatewayUrl, String formBody, String operation) {
        HttpResponse<String> response;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException exception) {
            throw providerTransportFailure(PayErrors.PROVIDER_TIMEOUT, operation, exception);
        } catch (IOException exception) {
            throw providerTransportFailure(PayErrors.PROVIDER_CALL_FAILED, operation, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw providerTransportFailure(PayErrors.PROVIDER_CALL_FAILED, operation, exception);
        } catch (Exception exception) {
            throw configurationFailure(operation + "-request-build", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw upstreamHttpFailure(operation, response);
        }
        return response.body();
    }

    private DependencyException upstreamHttpFailure(String operation, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        String upstreamCode = safeUpstreamCode(body);
        int responseBytes = body.getBytes(StandardCharsets.UTF_8).length;
        return new DependencyException(
                PayErrors.PROVIDER_CALL_FAILED,
                "Alipay provider request failed; operation=" + operation
                        + "; upstreamStatus=" + response.statusCode()
                        + "; upstreamCode=" + upstreamCode
                        + "; responseBytes=" + responseBytes,
                null,
                Map.of(
                        "operation", operation,
                        "upstreamStatus", response.statusCode(),
                        "upstreamCode", upstreamCode,
                        "responseBytes", responseBytes
                )
        );
    }

    private DependencyException providerApiFailure(String operation, JsonNode response) {
        String upstreamCode = safeUpstreamCode(response);
        return new DependencyException(
                PayErrors.PROVIDER_CALL_FAILED,
                "Alipay provider rejected request; operation=" + operation + "; upstreamCode=" + upstreamCode,
                null,
                Map.of("operation", operation, "upstreamCode", upstreamCode)
        );
    }

    private DependencyException providerTransportFailure(ErrorDefinition definition,
                                                         String operation,
                                                         Exception cause) {
        String exceptionType = cause.getClass().getName();
        return new DependencyException(
                definition,
                "Alipay provider transport failed; operation=" + operation + "; exceptionType=" + exceptionType,
                cause,
                Map.of("operation", operation, "exceptionType", exceptionType)
        );
    }

    private DependencyException invalidProviderResponse(String operation, Throwable cause) {
        String exceptionType = cause == null ? "none" : cause.getClass().getName();
        return new DependencyException(
                PayErrors.PROVIDER_RESPONSE_INVALID,
                "Alipay provider response invalid; operation=" + operation + "; exceptionType=" + exceptionType,
                cause,
                Map.of("operation", operation, "exceptionType", exceptionType)
        );
    }

    private BizException invalidCallback(String operation, Throwable cause) {
        String exceptionType = cause == null ? "none" : cause.getClass().getName();
        return new BizException(
                PayErrors.CALLBACK_INVALID,
                PayErrors.CALLBACK_INVALID.defaultUserMessage(),
                "Alipay callback rejected; operation=" + operation + "; exceptionType=" + exceptionType,
                cause,
                Map.of("operation", operation, "exceptionType", exceptionType)
        );
    }

    private SystemException configurationFailure(String operation, Throwable cause) {
        String exceptionType = cause == null ? "none" : cause.getClass().getName();
        return new SystemException(
                PayErrors.SERVICE_NOT_CONFIGURED,
                "Alipay payment configuration unavailable; operation=" + operation
                        + "; exceptionType=" + exceptionType,
                cause,
                Map.of("operation", operation, "exceptionType", exceptionType)
        );
    }

    private String safeUpstreamCode(String body) {
        if (body == null || body.isBlank()) {
            return "unknown";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode response = root.path("error_response");
            return safeUpstreamCode(response.isMissingNode() ? root : response);
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private String safeUpstreamCode(JsonNode response) {
        if (response == null || response.isMissingNode() || response.isNull()) {
            return "unknown";
        }
        String subCode = response.path("sub_code").asText("");
        String code = subCode.isBlank() ? response.path("code").asText("") : subCode;
        return code.matches("[A-Z0-9][A-Z0-9_.-]{0,63}") ? code : "unknown";
    }

    private String canonicalPayload(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> !"sign".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String stripPem(String value) {
        return value
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }
}
