package com.aiminilab.aitoolmarket.commerce;

import com.aiminilab.aitoolmarket.commerce.service.ModelGatewayException;
import com.aiminilab.aitoolmarket.commerce.service.ModelGatewayResult;
import com.aiminilab.aitoolmarket.commerce.service.ModelGatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class ModelGatewayServiceTest {

    @Test
    void parsesOpenAiCompatibleUsageIntoGatewayResult() throws IOException {
        HttpServer server = server(200, """
                {"choices":[{"message":{"content":"hello"}}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                """);
        try {
            ModelGatewayService service = new ModelGatewayService(jdbcTemplate(server, "openai_compatible"), new ObjectMapper());

            ModelGatewayResult result = service.chat(9L, 3L, 7L, "hi");

            assertThat(result.content()).isEqualTo("hello");
            assertThat(result.promptTokens()).isEqualTo(3);
            assertThat(result.completionTokens()).isEqualTo(2);
            assertThat(result.totalTokens()).isEqualTo(5);
            assertThat(result.estimatedCostCents()).isZero();
            assertThat(result.responseMetadataJson()).contains("openai_compatible");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesProviderRateLimitErrorsAsFallbackable() throws IOException {
        HttpServer server = server(429, "{\"error\":\"slow down\"}");
        try {
            ModelGatewayService service = new ModelGatewayService(jdbcTemplate(server, "openai_compatible"), new ObjectMapper());

            assertThatThrownBy(() -> service.chat(9L, 3L, 7L, "hi"))
                    .isInstanceOf(ModelGatewayException.class)
                    .satisfies(exception -> {
                        ModelGatewayException gatewayException = (ModelGatewayException) exception;
                        assertThat(gatewayException.getErrorCategory()).isEqualTo("RATE_LIMIT");
                        assertThat(gatewayException.isFallbackable()).isTrue();
                    });
        } finally {
            server.stop(0);
        }
    }

    private static JdbcTemplate jdbcTemplate(HttpServer server, String protocol) {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbcTemplate.queryForMap(anyString(), eq(7L), eq(3L)))
                .thenReturn(Map.of(
                        "node_code", "node-1",
                        "api_key", "test-key",
                        "base_url", "http://localhost:" + server.getAddress().getPort(),
                        "provider_protocol", protocol,
                        "model_name", "test-model",
                        "pool_model_name", "pool-model",
                        "timeout_seconds", 5
                ));
        Mockito.when(jdbcTemplate.queryForList(anyString(), eq(9L)))
                .thenReturn(List.of(Map.of("role", "USER", "content_text", "hi")));
        return jdbcTemplate;
    }

    private static HttpServer server(int statusCode, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
