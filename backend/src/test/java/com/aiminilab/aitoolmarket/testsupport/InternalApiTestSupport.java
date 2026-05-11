package com.aiminilab.aitoolmarket.testsupport;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class InternalApiTestSupport {

    private static final String INTERNAL_API_TOKEN = "local-internal-token";

    private InternalApiTestSupport() {
    }

    public static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request,
                                                       String method,
                                                       String path,
                                                       String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = UUID.randomUUID().toString();
        return request
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Nonce", nonce)
                .header("X-Internal-Signature", signature(method, path, timestamp, nonce, body));
    }

    private static String signature(String method, String path, String timestamp, String nonce, String body) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        String content = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(INTERNAL_API_TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
