package com.aiminilab.aitoolmarket.credit.alipay;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URLEncoder;
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

    public DefaultAlipayPagePayClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.properties = appProperties.getPayment().getAlipayPage();
        this.objectMapper = objectMapper;
    }

    @Override
    public AlipayPagePayResponse createPagePayOrder(AlipayPagePayRequest request) {
        requireEnabled();
        try {
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
            return new AlipayPagePayResponse(properties.getGatewayUrl() + "?" + encode(params));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "create Alipay page pay order failed: " + exception.getMessage());
        }
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
                .filter(entry -> !"sign".equals(entry.getKey()) && !"sign_type".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    private String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> url(entry.getKey()) + "=" + url(entry.getValue()))
                .collect(Collectors.joining("&"));
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
