package com.aiminilab.aitoolmarket.credit.wechat;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class DefaultWechatNativePayClient implements WechatNativePayClient {
    private static final String METHOD = "POST";
    private static final String GET_METHOD = "GET";
    private static final String NATIVE_PREPAY_PATH = "/v3/pay/transactions/native";
    private static final String ORDER_QUERY_PATH_PREFIX = "/v3/pay/transactions/out-trade-no/";
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AppProperties.WechatNative properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public DefaultWechatNativePayClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.properties = appProperties.getPayment().getWechatNative();
        this.objectMapper = objectMapper;
    }

    @Override
    public NativePrepayResponse createNativeOrder(NativePrepayRequest request) {
        ensureEnabled();
        try {
            String body = objectMapper.writeValueAsString(nativeOrderPayload(request));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getApiBaseUrl() + NATIVE_PREPAY_PATH))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorization(METHOD, NATIVE_PREPAY_PATH, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Native prepay failed: " + response.body());
            }
            verifyHttpResponseSignature(response);
            JsonNode json = objectMapper.readTree(response.body());
            String codeUrl = json.path("code_url").asText("");
            if (codeUrl.isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Native prepay response missing code_url");
            }
            return new NativePrepayResponse(codeUrl);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Native prepay request failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Native prepay request interrupted");
        }
    }

    @Override
    public WechatPayNotification queryNativeOrder(String orderNo) {
        ensureEnabled();
        try {
            String encodedOrderNo = URLEncoder.encode(orderNo, StandardCharsets.UTF_8);
            String pathWithQuery = ORDER_QUERY_PATH_PREFIX + encodedOrderNo + "?mchid="
                    + URLEncoder.encode(properties.getMchid(), StandardCharsets.UTF_8);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getApiBaseUrl() + pathWithQuery))
                    .header("Accept", "application/json")
                    .header("Authorization", authorization(GET_METHOD, pathWithQuery, ""))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Native order query failed: " + response.body());
            }
            verifyHttpResponseSignature(response);
            return notificationFromTransaction(objectMapper.readTree(response.body()));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Native order query failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Native order query interrupted");
        }
    }

    @Override
    public WechatPayNotification parseNotification(WechatPayCallbackHeaders headers, String body) {
        ensureEnabled();
        verifyCallbackSignature(headers, body);
        try {
            JsonNode payload = objectMapper.readTree(body);
            JsonNode resource = payload.path("resource");
            String plain = decryptResource(
                    resource.path("nonce").asText(),
                    resource.path("associated_data").asText(""),
                    resource.path("ciphertext").asText()
            );
            JsonNode transaction = objectMapper.readTree(plain);
            return notificationFromTransaction(transaction);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment notification decrypt failed");
        }
    }

    private Map<String, Object> nativeOrderPayload(NativePrepayRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appid", properties.getAppid());
        payload.put("mchid", properties.getMchid());
        payload.put("description", request.description());
        payload.put("out_trade_no", request.orderNo());
        payload.put("time_expire", RFC3339.format(request.expiresAt().withNano(0).atOffset(ZoneOffset.ofHours(8))));
        payload.put("notify_url", properties.getNotifyUrl());
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", request.totalAmountFen());
        amount.put("currency", request.currency() == null || request.currency().isBlank() ? "CNY" : request.currency());
        payload.put("amount", amount);
        return payload;
    }

    private WechatPayNotification notificationFromTransaction(JsonNode transaction) {
        JsonNode amount = transaction.path("amount");
        return new WechatPayNotification(
                transaction.path("appid").asText(),
                transaction.path("mchid").asText(),
                transaction.path("out_trade_no").asText(),
                transaction.path("transaction_id").asText(),
                transaction.path("trade_type").asText(),
                transaction.path("trade_state").asText(),
                amount.path("total").asInt(),
                amount.path("currency").asText("CNY")
        );
    }

    private String authorization(String method, String path, String body) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String message = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = sign(message, loadPrivateKey(properties.getMerchantPrivateKeyPath()));
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + properties.getMchid()
                + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature
                + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + properties.getMerchantSerialNo()
                + "\"";
    }

    private void verifyCallbackSignature(WechatPayCallbackHeaders headers, String body) {
        if (headers == null || blank(headers.serial()) || blank(headers.signature())
                || blank(headers.timestamp()) || blank(headers.nonce())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment callback signature headers missing");
        }
        if (!headers.serial().equals(properties.getWechatPayPublicKeyId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment callback public key mismatch");
        }
        String message = headers.timestamp() + "\n" + headers.nonce() + "\n" + body + "\n";
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(loadPublicKey(properties.getWechatPayPublicKeyPath()));
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            boolean ok = verifier.verify(Base64.getDecoder().decode(headers.signature()));
            if (!ok) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment callback signature invalid");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment callback signature verify failed");
        }
    }

    private void verifyHttpResponseSignature(HttpResponse<String> response) {
        WechatPayCallbackHeaders headers = new WechatPayCallbackHeaders(
                response.headers().firstValue("Wechatpay-Serial").orElse(""),
                response.headers().firstValue("Wechatpay-Signature").orElse(""),
                response.headers().firstValue("Wechatpay-Timestamp").orElse(""),
                response.headers().firstValue("Wechatpay-Nonce").orElse("")
        );
        verifyCallbackSignature(headers, response.body());
    }

    private String decryptResource(String nonce, String associatedData, String ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(properties.getApiV3Key().getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        if (associatedData != null && !associatedData.isBlank()) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
        return new String(plain, StandardCharsets.UTF_8);
    }

    private PrivateKey loadPrivateKey(String keyPath) {
        try {
            String pem = Files.readString(Path.of(keyPath), StandardCharsets.UTF_8);
            String content = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] bytes = Base64.getDecoder().decode(content);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat merchant private key cannot be loaded");
        }
    }

    private PublicKey loadPublicKey(String keyPath) {
        try {
            String pem = Files.readString(Path.of(keyPath), StandardCharsets.UTF_8);
            String content = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] bytes = Base64.getDecoder().decode(content);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Pay public key cannot be loaded");
        }
    }

    private String sign(String message, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat request signature failed");
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()
                || blank(properties.getAppid())
                || blank(properties.getMchid())
                || blank(properties.getMerchantSerialNo())
                || blank(properties.getMerchantPrivateKeyPath())
                || blank(properties.getApiV3Key())
                || blank(properties.getWechatPayPublicKeyId())
                || blank(properties.getWechatPayPublicKeyPath())
                || blank(properties.getNotifyUrl())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat Native payment is not configured");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
