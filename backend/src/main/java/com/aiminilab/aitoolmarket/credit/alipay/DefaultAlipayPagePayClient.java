package com.aiminilab.aitoolmarket.credit.alipay;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
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
        if (isPagePayMode()) {
            String launchPath = "/api/v1/pay/alipay/page/launch?orderNo=" + url(request.outTradeNo());
            return AlipayPagePayResponse.pageRedirect(launchPath);
        }
        try {
            String qrCode = createPrecreateQrCode(request);
            return AlipayPagePayResponse.qr(qrCode);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "create Alipay QR pay order failed: " + exception.getMessage());
        }
    }

    @Override
    public String buildPagePayHtml(AlipayPagePayRequest request) {
        requireEnabled();
        try {
            return buildAutoSubmitForm(request);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "build Alipay page pay form failed: " + exception.getMessage());
        }
    }

    private String createPrecreateQrCode(AlipayPagePayRequest request) throws Exception {
        Map<String, String> params = buildSignedParams(request, "alipay.trade.precreate", false);
        String responseBody = postForm(properties.getGatewayUrl(), encode(params));
        JsonNode json = objectMapper.readTree(responseBody);
        JsonNode precreate = json.path("alipay_trade_precreate_response");
        String code = precreate.path("code").asText("");
        if (!"10000".equals(code)) {
            String msg = precreate.path("sub_msg").asText(precreate.path("msg").asText("Alipay precreate failed"));
            throw new BusinessException(ErrorCode.PARAM_ERROR, "create Alipay QR pay order failed: " + normalizeAlipayError(msg));
        }
        String qrCode = precreate.path("qr_code").asText("");
        if (qrCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "create Alipay QR pay order failed: missing qr_code");
        }
        return qrCode;
    }

    private String buildAutoSubmitForm(AlipayPagePayRequest request) throws Exception {
        Map<String, String> params = buildSignedParams(request, "alipay.trade.page.pay", true);
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

    private Map<String, String> buildSignedParams(AlipayPagePayRequest request, String method, boolean pagePay) throws Exception {
        Map<String, String> bizContent = new TreeMap<>();
        bizContent.put("out_trade_no", request.outTradeNo());
        bizContent.put("total_amount", request.totalAmount().setScale(2).toPlainString());
        bizContent.put("subject", request.subject());
        if (pagePay) {
            bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        }
        if (request.expiresAt() != null) {
            bizContent.put("time_expire", EXPIRE_FORMAT.format(request.expiresAt()));
        }
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", properties.getAppId());
        params.put("method", method);
        params.put("format", pagePay ? "JSON" : "JSON");
        params.put("charset", "UTF-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", EXPIRE_FORMAT.format(java.time.LocalDateTime.now()));
        params.put("version", "1.0");
        params.put("notify_url", properties.getNotifyUrl());
        if (pagePay && StringUtils.hasText(properties.getReturnUrl())) {
            params.put("return_url", properties.getReturnUrl());
        }
        params.put("biz_content", objectMapper.writeValueAsString(bizContent));
        params.put("sign", sign(canonicalPayload(params), privateKey()));
        return params;
    }

    private boolean isPagePayMode() {
        return "PAGE".equalsIgnoreCase(properties.getPayMode());
    }

    @Override
    public AlipayNotification parseNotification(Map<String, String> params) {
        requireEnabled();
        if (!verify(params)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay notification signature verification failed");
        }
        return new AlipayNotification(
                params.get("app_id"),
                params.get("out_trade_no"),
                params.get("trade_no"),
                params.get("trade_status"),
                new BigDecimal(params.getOrDefault("total_amount", "0"))
        );
    }

    private void requireEnabled() {
        if (!properties.isEnabled()
                || !StringUtils.hasText(properties.getAppId())
                || !StringUtils.hasText(properties.getMerchantPrivateKey())
                || !StringUtils.hasText(properties.getAlipayPublicKey())
                || !StringUtils.hasText(properties.getNotifyUrl())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay page pay is not configured");
        }
    }

    private boolean verify(Map<String, String> params) {
        String sign = params.get("sign");
        if (!StringUtils.hasText(sign)) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey());
            verifier.update(canonicalPayload(params).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(sign));
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

    private String canonicalPayload(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> !"sign".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    private String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> url(entry.getKey()) + "=" + url(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String postForm(String url, String formBody) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay gateway request failed: " + response.body());
        }
        return response.body();
    }

    private String normalizeAlipayError(String message) {
        if (message == null || message.isBlank()) {
            return "Alipay precreate failed";
        }
        if (message.contains("接口调用权限不足")) {
            if (isPagePayMode()) {
                return "支付宝应用缺少 alipay.trade.page.pay（电脑网站支付）接口权限，请在开放平台确认电脑支付已签约并添加到本应用";
            }
            return "支付宝应用缺少 alipay.trade.precreate（扫码预下单/当面付）接口权限。"
                    + " 若你已开通的是「电脑网站支付」，请将 ALIPAY_PAY_MODE 设为 PAGE；若需扫码支付，请额外开通「当面付」产品";
        }
        return message;
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
