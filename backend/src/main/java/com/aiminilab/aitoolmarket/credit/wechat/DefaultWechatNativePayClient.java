package com.aiminilab.aitoolmarket.credit.wechat;

import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;
import com.aiminilab.aitoolmarket.common.error.PayErrors;
import com.aiminilab.aitoolmarket.common.exception.AppException;
import com.aiminilab.aitoolmarket.common.exception.BizException;
import com.aiminilab.aitoolmarket.common.exception.DependencyException;
import com.aiminilab.aitoolmarket.common.exception.SystemException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.net.http.HttpTimeoutException;
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
import java.time.Instant;
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
    private static final long CALLBACK_MAX_SKEW_SECONDS = 300;

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
                throw upstreamHttpFailure("native-prepay", response);
            }
            verifyHttpResponseSignature(response);
            JsonNode json = objectMapper.readTree(response.body());
            String codeUrl = json.path("code_url").asText("");
            if (codeUrl.isBlank()) {
                throw invalidProviderResponse("native-prepay", "requiredField=code_url", null);
            }
            return new NativePrepayResponse(codeUrl);
        } catch (JsonProcessingException exception) {
            throw invalidProviderResponse("native-prepay", "responseJsonInvalid", exception);
        } catch (HttpTimeoutException exception) {
            throw providerTransportFailure(PayErrors.PROVIDER_TIMEOUT, "native-prepay", exception);
        } catch (IOException exception) {
            throw providerTransportFailure(PayErrors.PROVIDER_CALL_FAILED, "native-prepay", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw providerTransportFailure(PayErrors.PROVIDER_CALL_FAILED, "native-prepay", exception);
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
                throw upstreamHttpFailure("order-query", response);
            }
            verifyHttpResponseSignature(response);
            return notificationFromTransaction(objectMapper.readTree(response.body()));
        } catch (JsonProcessingException exception) {
            throw invalidProviderResponse("order-query", "responseJsonInvalid", exception);
        } catch (HttpTimeoutException exception) {
            throw providerTransportFailure(PayErrors.PROVIDER_TIMEOUT, "order-query", exception);
        } catch (IOException exception) {
            throw providerTransportFailure(PayErrors.PROVIDER_CALL_FAILED, "order-query", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw providerTransportFailure(PayErrors.PROVIDER_CALL_FAILED, "order-query", exception);
        }
    }

    @Override
    public boolean closeNativeOrder(String orderNo) {
        ensureEnabled();
        try {
            String path = ORDER_QUERY_PATH_PREFIX + URLEncoder.encode(orderNo, StandardCharsets.UTF_8) + "/close";
            String body = objectMapper.writeValueAsString(Map.of("mchid", properties.getMchid()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getApiBaseUrl() + path))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorization(METHOD, path, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 204) {
                return true;
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                verifyHttpResponseSignature(response);
                return true;
            }
            return false;
        } catch (JsonProcessingException exception) {
            throw invalidProviderResponse("order-close", "requestJsonSerializationFailed", exception);
        } catch (HttpTimeoutException exception) {
            throw providerTransportFailure(PayErrors.PROVIDER_TIMEOUT, "order-close", exception);
        } catch (IOException exception) {
            throw providerTransportFailure(PayErrors.PROVIDER_CALL_FAILED, "order-close", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw providerTransportFailure(PayErrors.PROVIDER_CALL_FAILED, "order-close", exception);
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
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCallback("notificationDecryptOrParseFailed", exception);
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
        verifySignature(headers, body, false);
    }

    private void verifySignature(WechatPayCallbackHeaders headers, String body, boolean providerResponse) {
        if (headers == null || blank(headers.serial()) || blank(headers.signature())
                || blank(headers.timestamp()) || blank(headers.nonce())) {
            throw signatureFailure(providerResponse, "signatureHeadersMissing", null);
        }
        if (!headers.serial().equals(properties.getWechatPayPublicKeyId())) {
            throw signatureFailure(providerResponse, "publicKeyIdMismatch", null);
        }
        long now = Instant.now().getEpochSecond();
        long callbackTs;
        try {
            callbackTs = Long.parseLong(headers.timestamp());
        } catch (NumberFormatException exception) {
            throw signatureFailure(providerResponse, "timestampInvalid", exception);
        }
        if (Math.abs(now - callbackTs) > CALLBACK_MAX_SKEW_SECONDS) {
            throw signatureFailure(providerResponse, "timestampExpired", null);
        }
        String message = headers.timestamp() + "\n" + headers.nonce() + "\n" + body + "\n";
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(loadPublicKey(properties.getWechatPayPublicKeyPath()));
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            boolean ok = verifier.verify(Base64.getDecoder().decode(headers.signature()));
            if (!ok) {
                throw signatureFailure(providerResponse, "signatureInvalid", null);
            }
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw signatureFailure(providerResponse, "signatureVerificationFailed", exception);
        }
    }

    private void verifyHttpResponseSignature(HttpResponse<String> response) {
        WechatPayCallbackHeaders headers = new WechatPayCallbackHeaders(
                response.headers().firstValue("Wechatpay-Serial").orElse(""),
                response.headers().firstValue("Wechatpay-Signature").orElse(""),
                response.headers().firstValue("Wechatpay-Timestamp").orElse(""),
                response.headers().firstValue("Wechatpay-Nonce").orElse("")
        );
        verifySignature(headers, response.body(), true);
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
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            // WeChat 商户私钥常见两种 PEM 头：
            // 1) BEGIN PRIVATE KEY   -> PKCS#8
            // 2) BEGIN RSA PRIVATE KEY -> PKCS#1
            if (pem.contains("-----BEGIN PRIVATE KEY-----")) {
                String content = pem
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
                byte[] bytes = Base64.getDecoder().decode(content);
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
            }

            if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
                String content = pem
                        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                        .replace("-----END RSA PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
                byte[] bytes = Base64.getDecoder().decode(content);
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(rsaPkcs1ToPkcs8(bytes)));
            }

            throw new IllegalArgumentException("Unsupported WeChat merchant private key PEM header");
        } catch (Exception exception) {
            throw configurationFailure("merchantPrivateKeyLoadFailed", exception);
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
            throw configurationFailure("wechatPayPublicKeyLoadFailed", exception);
        }
    }

    private String sign(String message, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception exception) {
            throw configurationFailure("requestSignatureFailed", exception);
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
            throw configurationFailure("requiredConfigurationMissing", null);
        }
    }

    private DependencyException upstreamHttpFailure(String operation, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        String upstreamCode = safeUpstreamCode(body);
        String developerMessage = "WeChat Pay provider request failed; operation=" + operation
                + "; upstreamStatus=" + response.statusCode()
                + "; upstreamCode=" + upstreamCode
                + "; responseBytes=" + body.getBytes(StandardCharsets.UTF_8).length;
        return new DependencyException(
                PayErrors.PROVIDER_CALL_FAILED,
                developerMessage,
                null,
                Map.of(
                        "provider", "wechat_pay",
                        "operation", operation,
                        "upstreamStatus", response.statusCode(),
                        "upstreamCode", upstreamCode,
                        "responseBytes", body.getBytes(StandardCharsets.UTF_8).length
                )
        );
    }

    private DependencyException providerTransportFailure(ErrorDefinition definition,
                                                         String operation,
                                                         Exception cause) {
        return new DependencyException(
                definition,
                "WeChat Pay provider transport failed; operation=" + operation
                        + "; exceptionType=" + cause.getClass().getName(),
                cause,
                Map.of("provider", "wechat_pay", "operation", operation)
        );
    }

    private DependencyException invalidProviderResponse(String operation, String reason, Throwable cause) {
        return new DependencyException(
                PayErrors.PROVIDER_RESPONSE_INVALID,
                "WeChat Pay provider response rejected; operation=" + operation + "; reason=" + reason,
                cause,
                Map.of("provider", "wechat_pay", "operation", operation, "reason", reason)
        );
    }

    private AppException signatureFailure(boolean providerResponse, String reason, Throwable cause) {
        if (providerResponse) {
            return invalidProviderResponse("response-signature", reason, cause);
        }
        return invalidCallback(reason, cause);
    }

    private BizException invalidCallback(String reason, Throwable cause) {
        return new BizException(
                PayErrors.CALLBACK_INVALID,
                PayErrors.CALLBACK_INVALID.defaultUserMessage(),
                "WeChat Pay callback rejected; reason=" + reason,
                cause,
                Map.of("provider", "wechat_pay", "operation", "payment-callback", "reason", reason)
        );
    }

    private SystemException configurationFailure(String reason, Throwable cause) {
        return new SystemException(
                PayErrors.SERVICE_NOT_CONFIGURED,
                "WeChat Pay configuration unavailable; reason=" + reason,
                cause,
                Map.of("provider", "wechat_pay", "reason", reason)
        );
    }

    private String safeUpstreamCode(String body) {
        if (body == null || body.isBlank()) {
            return "unknown";
        }
        try {
            String code = objectMapper.readTree(body).path("code").asText("");
            return code.matches("[A-Za-z0-9_.-]{1,64}") ? code : "unknown";
        } catch (JsonProcessingException ignored) {
            return "unknown";
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Wrap PKCS#1 RSA private key bytes in a PKCS#8 PrivateKeyInfo envelope.
     * PKCS1EncodedKeySpec is only available from newer JDKs, so Java 17 needs this conversion.
     */
    private static byte[] rsaPkcs1ToPkcs8(byte[] pkcs1) {
        byte[] version = new byte[] {0x02, 0x01, 0x00};
        byte[] algorithmIdentifier = new byte[] {
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] privateKeyOctet = encodeDerOctetString(pkcs1);
        return encodeDerSequence(concat(version, algorithmIdentifier, privateKeyOctet));
    }

    private static byte[] encodeDerSequence(byte[] content) {
        return encodeDerTag((byte) 0x30, content);
    }

    private static byte[] encodeDerOctetString(byte[] content) {
        return encodeDerTag((byte) 0x04, content);
    }

    private static byte[] encodeDerTag(byte tag, byte[] content) {
        byte[] length = encodeDerLength(content.length);
        byte[] encoded = new byte[1 + length.length + content.length];
        encoded[0] = tag;
        System.arraycopy(length, 0, encoded, 1, length.length);
        System.arraycopy(content, 0, encoded, 1 + length.length, content.length);
        return encoded;
    }

    private static byte[] encodeDerLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("DER length cannot be negative");
        }
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        if (length <= 0xFF) {
            return new byte[] {(byte) 0x81, (byte) length};
        }
        if (length <= 0xFFFF) {
            return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
        }
        if (length <= 0xFFFFFF) {
            return new byte[] {(byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length};
        }
        throw new IllegalArgumentException("DER length too large");
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] merged = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, merged, offset, part.length);
            offset += part.length;
        }
        return merged;
    }
}
