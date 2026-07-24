package com.aiminilab.aitoolmarket.credit.alipay;

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
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DefaultAlipayPagePayClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        MDC.remove("traceId");
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void queryRejectsInvalidGatewayResponseSignature() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String responseContent = successfulQueryResponse();
        startServer(responseContent, Base64.getEncoder().encodeToString("invalid".getBytes(StandardCharsets.UTF_8)));

        DefaultAlipayPagePayClient client = client(keyPair);

        assertThatThrownBy(() -> client.queryOrder("ORDER-1"))
                .isInstanceOf(DependencyException.class)
                .hasMessageContaining("response-signature");
    }

    @Test
    void gatewayHttpFailureBodyIsExcludedFromLegacyAndV2UserResponses() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String secretBody = "{\"error_response\":{\"code\":\"40004\","
                + "\"sub_code\":\"ACQ.SYSTEM_ERROR\","
                + "\"sub_msg\":\"password=provider-secret\","
                + "\"token\":\"upstream-token\"}}";
        startRejectedServer(secretBody);

        DependencyException exception = catchThrowableOfType(
                () -> client(keyPair).queryOrder("ORDER-1"),
                DependencyException.class
        );

        assertThat(exception.getErrorDefinition()).isEqualTo(PayErrors.PROVIDER_CALL_FAILED);
        assertThat(exception.getUserMessage()).isEqualTo("支付服务暂不可用，请稍后重试");
        assertThat(exception.getDeveloperMessage())
                .contains("operation=alipay.trade.query", "upstreamStatus=502",
                        "upstreamCode=ACQ.SYSTEM_ERROR", "responseBytes=")
                .doesNotContain("provider-secret", "upstream-token", "password");
        assertThat(exception.getLogContext().toString())
                .doesNotContain("provider-secret", "upstream-token", "password");

        MDC.put("traceId", "trace-alipay-1");
        ResponseEntity<Object> legacyResponse = legacyHandler().handleAppException(exception, userRequest());
        assertThat(legacyResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(legacyResponse.getBody()).isInstanceOf(ApiResponse.class);
        ApiResponse<?> legacyBody = (ApiResponse<?>) legacyResponse.getBody();
        assertThat(legacyBody.code()).isEqualTo("SYSTEM_ERROR");
        assertThat(legacyBody.message()).isEqualTo("支付服务暂不可用，请稍后重试");
        assertThat(legacyBody.toString()).doesNotContain("provider-secret", "upstream-token", "password");

        ResponseEntity<Object> v2Response = v2Handler().handleAppException(exception, userRequest());
        assertThat(v2Response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(v2Response.getBody()).isInstanceOf(UserErrorResponse.class);
        UserErrorResponse v2Body = (UserErrorResponse) v2Response.getBody();
        assertThat(v2Body.errorCode()).isEqualTo("PAY_001");
        assertThat(v2Body.userMessage()).isEqualTo("支付服务暂不可用，请稍后重试");
        assertThat(v2Body.toString()).doesNotContain("provider-secret", "upstream-token", "password");
    }

    @Test
    void signedGatewayBusinessFailureDoesNotExposeSubMessage() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String responseContent = "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                + "\"sub_code\":\"ACQ.SYSTEM_ERROR\","
                + "\"sub_msg\":\"password=provider-secret token=upstream-token\"}";
        startServer(responseContent, sign(responseContent, keyPair));

        DependencyException exception = catchThrowableOfType(
                () -> client(keyPair).queryOrder("ORDER-1"),
                DependencyException.class
        );

        assertThat(exception.getErrorDefinition()).isEqualTo(PayErrors.PROVIDER_CALL_FAILED);
        assertThat(exception.getUserMessage()).isEqualTo("支付服务暂不可用，请稍后重试");
        assertThat(exception.getDeveloperMessage())
                .contains("operation=order-query", "upstreamCode=ACQ.SYSTEM_ERROR")
                .doesNotContain("provider-secret", "upstream-token", "password", "sub_msg");
        assertThat(exception.getLogContext().toString())
                .doesNotContain("provider-secret", "upstream-token", "password", "sub_msg");
    }

    @Test
    void queryAcceptsValidGatewayResponseSignature() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String responseContent = successfulQueryResponse();
        startServer(responseContent, sign(responseContent, keyPair));

        AlipayTradeQueryResult result = client(keyPair).queryOrder("ORDER-1");

        assertThat(result.outTradeNo()).isEqualTo("ORDER-1");
        assertThat(result.tradeNo()).isEqualTo("TRADE-1");
        assertThat(result.tradeStatus()).isEqualTo("TRADE_SUCCESS");
    }

    @Test
    void queryVerifiesTheExactGatewayResponsePayload() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String responseContent = "{ \"code\": \"10000\", \"msg\": \"Success\", "
                + "\"out_trade_no\": \"ORDER-1\", \"trade_no\": \"TRADE-1\", "
                + "\"trade_status\": \"TRADE_SUCCESS\", \"total_amount\": \"10.00\" }";
        startServer(responseContent, sign(responseContent, keyPair));

        AlipayTradeQueryResult result = client(keyPair).queryOrder("ORDER-1");

        assertThat(result.tradeStatus()).isEqualTo("TRADE_SUCCESS");
    }

    private DefaultAlipayPagePayClient client(KeyPair keyPair) {
        AppProperties appProperties = new AppProperties();
        AppProperties.AlipayPage properties = appProperties.getPayment().getAlipayPage();
        properties.setEnabled(true);
        properties.setAppId("test-app");
        properties.setMerchantPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        properties.setAlipayPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        properties.setNotifyUrl("https://example.com/alipay/notify");
        properties.setGatewayUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/gateway.do");
        return new DefaultAlipayPagePayClient(appProperties, new ObjectMapper());
    }

    private void startServer(String responseContent, String signature) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gateway.do", exchange -> {
            byte[] body = ("{\"alipay_trade_query_response\":" + responseContent
                    + ",\"sign\":\"" + signature + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private void startRejectedServer(String responseContent) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gateway.do", exchange -> {
            byte[] body = responseContent.getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(502, body.length);
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
        return new MockHttpServletRequest("GET", "/api/v1/credits/recharge-orders/1");
    }

    private String successfulQueryResponse() {
        return "{\"code\":\"10000\",\"msg\":\"Success\",\"out_trade_no\":\"ORDER-1\","
                + "\"trade_no\":\"TRADE-1\",\"trade_status\":\"TRADE_SUCCESS\",\"total_amount\":\"10.00\"}";
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String sign(String content, KeyPair keyPair) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
