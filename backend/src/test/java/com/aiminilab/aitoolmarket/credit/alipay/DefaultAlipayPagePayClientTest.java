package com.aiminilab.aitoolmarket.credit.alipay;

import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultAlipayPagePayClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
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
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("signature");
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
