package com.aiminilab.aitoolmarket.credit.wechat;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.error.ErrorContractMode;
import com.aiminilab.aitoolmarket.common.error.ErrorContractProperties;
import com.aiminilab.aitoolmarket.common.error.ErrorContractResponseFactory;
import com.aiminilab.aitoolmarket.common.error.PayErrors;
import com.aiminilab.aitoolmarket.common.error.UserErrorResponse;
import com.aiminilab.aitoolmarket.common.exception.DependencyException;
import com.aiminilab.aitoolmarket.common.exception.GlobalExceptionHandler;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DefaultWechatNativePayClientTest {

    private static final String SECRET_UPSTREAM_BODY = "{\"code\":\"INVALID_REQUEST\","
            + "\"message\":\"merchant rejected password=provider-secret\","
            + "\"token\":\"upstream-token\"}";

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void cleanUp() {
        MDC.remove("traceId");
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void upstreamFailureBodyIsExcludedFromLegacyAndV2UserResponses() throws Exception {
        startRejectedPrepayServer();
        DefaultWechatNativePayClient client = client();

        DependencyException exception = catchThrowableOfType(
                () -> client.createNativeOrder(new NativePrepayRequest(
                        "ORDER-1",
                        "Test recharge",
                        100,
                        "CNY",
                        LocalDateTime.now().plusMinutes(30)
                )),
                DependencyException.class
        );

        assertThat(exception.getErrorDefinition()).isEqualTo(PayErrors.PROVIDER_CALL_FAILED);
        assertThat(exception.getUserMessage()).isEqualTo("支付服务暂不可用，请稍后重试");
        assertThat(exception.getDeveloperMessage())
                .contains("operation=native-prepay", "upstreamStatus=400", "upstreamCode=INVALID_REQUEST")
                .doesNotContain("provider-secret", "upstream-token", "merchant rejected");
        assertThat(exception.getLogContext().toString())
                .doesNotContain("provider-secret", "upstream-token", "merchant rejected");

        MDC.put("traceId", "trace-pay-1");
        ResponseEntity<Object> legacyResponse = legacyHandler().handleAppException(exception, userRequest());
        assertThat(legacyResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(legacyResponse.getBody()).isInstanceOf(ApiResponse.class);
        ApiResponse<?> legacyBody = (ApiResponse<?>) legacyResponse.getBody();
        assertThat(legacyBody.code()).isEqualTo("SYSTEM_ERROR");
        assertThat(legacyBody.message()).isEqualTo("支付服务暂不可用，请稍后重试");
        assertThat(legacyBody.toString()).doesNotContain("provider-secret", "upstream-token", "merchant rejected");

        ResponseEntity<Object> v2Response = v2Handler().handleAppException(exception, userRequest());
        assertThat(v2Response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(v2Response.getBody()).isInstanceOf(UserErrorResponse.class);
        UserErrorResponse v2Body = (UserErrorResponse) v2Response.getBody();
        assertThat(v2Body.errorCode()).isEqualTo("PAY_001");
        assertThat(v2Body.userMessage()).isEqualTo("支付服务暂不可用，请稍后重试");
        assertThat(v2Body.toString()).doesNotContain("provider-secret", "upstream-token", "merchant rejected");
    }

    private DefaultWechatNativePayClient client() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        Path privateKey = tempDir.resolve("merchant-private.pem");
        Path publicKey = tempDir.resolve("wechat-pay-public.pem");
        Files.writeString(
                privateKey,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                publicKey,
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n",
                StandardCharsets.UTF_8
        );

        AppProperties appProperties = new AppProperties();
        AppProperties.WechatNative properties = appProperties.getPayment().getWechatNative();
        properties.setEnabled(true);
        properties.setAppid("test-app-id");
        properties.setMchid("test-merchant-id");
        properties.setMerchantSerialNo("test-merchant-serial");
        properties.setMerchantPrivateKeyPath(privateKey.toString());
        properties.setApiV3Key("0123456789abcdef0123456789abcdef");
        properties.setWechatPayPublicKeyId("test-public-key-id");
        properties.setWechatPayPublicKeyPath(publicKey.toString());
        properties.setNotifyUrl("https://example.com/api/v1/pay/wechat/native/notify");
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new DefaultWechatNativePayClient(appProperties, new ObjectMapper());
    }

    private void startRejectedPrepayServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/pay/transactions/native", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = SECRET_UPSTREAM_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private GlobalExceptionHandler legacyHandler() {
        return new GlobalExceptionHandler(new ErrorContractResponseFactory(new ErrorContractProperties()));
    }

    private GlobalExceptionHandler v2Handler() {
        ErrorContractProperties properties = new ErrorContractProperties();
        properties.setMode(ErrorContractMode.V2);
        return new GlobalExceptionHandler(new ErrorContractResponseFactory(properties));
    }

    private MockHttpServletRequest userRequest() {
        return new MockHttpServletRequest("POST", "/api/v1/credit/recharge-orders");
    }
}
