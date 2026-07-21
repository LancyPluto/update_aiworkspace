package com.aiminilab.aitoolmarket.agent.connectivity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelsAccountConnectivityProbeTest {

    private final List<HttpServer> servers = new ArrayList<>();
    private final ModelsAccountConnectivityProbe probe = new ModelsAccountConnectivityProbe(
            new ModelsEndpointResolver(),
            new AccountProbeErrorClassifier()
    );

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void sendsAuthenticatedGetWithoutModelQueryOrBody() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        String baseUrl = startServer(200, "{\"data\":[]}", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            requestBody.set(exchange.getRequestBody().readAllBytes());
        });

        ConnectivityProbeResult result = probe.probeAccount(context(baseUrl, "secret-key")).orElseThrow();

        assertThat(result.success()).isTrue();
        assertThat(result.stage()).isEqualTo("ACCOUNT_MODELS");
        assertThat(method.get()).isEqualTo("GET");
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
        assertThat(query.get()).isNull();
        assertThat(requestBody.get()).isEmpty();
    }

    @Test
    void keepsQuotaResponsesActiveWithWarning() throws Exception {
        String forbidden = startServer(403, "{\"message\":\"free quota has been exhausted\"}", exchange -> { });
        String rateLimited = startServer(429, "too many requests", exchange -> { });

        ConnectivityProbeResult forbiddenResult = probe.probeAccount(context(forbidden, "key")).orElseThrow();
        ConnectivityProbeResult rateLimitResult = probe.probeAccount(context(rateLimited, "key")).orElseThrow();

        assertThat(forbiddenResult.success()).isTrue();
        assertThat(forbiddenResult.warning()).contains("额度");
        assertThat(rateLimitResult.success()).isTrue();
        assertThat(rateLimitResult.warning()).contains("限流");
    }

    @Test
    void neverFallsThroughForAgnesCredentialEndpointOrServerFailures() throws Exception {
        String unauthorized = startServer(401, "unauthorized", exchange -> { });
        String unsupported = startServer(404, "not found", exchange -> { });
        String unavailable = startServer(503, "unavailable", exchange -> { });

        assertFailure(unauthorized, 401);
        ConnectivityProbeResult unsupportedResult = assertFailure(unsupported, 404);
        assertThat(unsupportedResult.message()).contains("未配置其他账户级探活方式");
        assertFailure(unavailable, 503);
    }

    @Test
    void leavesUnsupportedVendorsToTheirExistingStrategies() {
        Optional<ConnectivityProbeResult> result = probe.probeAccount(new AccountProbeContext(
                1L, "openai", "https://api.openai.com/v1", "key", null, null, null));

        assertThat(result).isEmpty();
    }

    private ConnectivityProbeResult assertFailure(String baseUrl, int status) {
        ConnectivityProbeResult result = probe.probeAccount(context(baseUrl, "key")).orElseThrow();
        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(status);
        return result;
    }

    private AccountProbeContext context(String baseUrl, String apiKey) {
        return new AccountProbeContext(1L, "agnes", baseUrl, apiKey, null, null, null);
    }

    private String startServer(int status, String responseBody, ExchangeObserver observer) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            observer.observe(exchange);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @FunctionalInterface
    private interface ExchangeObserver {
        void observe(HttpExchange exchange) throws IOException;
    }
}
