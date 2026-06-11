package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:model_vendor_account_discovery_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class ModelVendorAccountDiscoveryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentModelConfigMapper agentModelConfigMapper;

    @Test
    void adminCanDiscoverOpenAiCompatibleModelsAndUpsertConfigs() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "gpt-4o-mini", "object": "model"},
                    {"id": "gpt-image-2", "object": "model"},
                    {"id": "kling-v2-6", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            Long accountId = createVendorAccount(adminToken, "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()));

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.imported").value(3))
                    .andExpect(jsonPath("$.data.updated").value(0))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='gpt-4o-mini')].capabilities[0]").value("TEXT_GENERATION"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='gpt-image-2')].capabilities[0]").value("IMAGE_GENERATION"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='kling-v2-6')].capabilities[0]").value("VIDEO_GENERATION"));

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.imported").value(0))
                    .andExpect(jsonPath("$.data.updated").value(3));

            String list = mockMvc.perform(get("/api/admin/v1/agent/model-config/list")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(list).contains("gpt-4o-mini", "gpt-image-2", "kling-v2-6");
            assertThat(list).contains("TEXT_GENERATION", "IMAGE_GENERATION", "VIDEO_GENERATION");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void adminCanDiscoverAgnesModelsAndMapThemToAgnesProviders() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "agnes-2.0-flash", "object": "model"},
                    {"id": "agnes-image-2.0-flash", "object": "model"},
                    {"id": "agnes-image-2.1-flash", "object": "model"},
                    {"id": "agnes-video-v2.0", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            Long accountId = createVendorAccount(
                    adminToken,
                    "agnes",
                    "Agnes Gateway",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort())
            );

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.imported").value(4))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='agnes-2.0-flash')].provider").value("agnes_chat"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='agnes-image-2.0-flash')].provider").value("agnes_images"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='agnes-image-2.1-flash')].provider").value("agnes_images"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='agnes-video-v2.0')].provider").value("agnes_video"));

            AgentModelConfig image = agentModelConfigMapper.findActiveByVendorAccountAndModelName(accountId, "agnes-image-2.1-flash");
            AgentModelConfig video = agentModelConfigMapper.findActiveByVendorAccountAndModelName(accountId, "agnes-video-v2.0");
            assertThat(image.getExtraAuthJson()).contains("responseFormatLocation", "jsonImageArray");
            assertThat(video.getExtraAuthJson()).contains("resultEndpointPath", "videoIdQuery");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer modelsServer(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer discovery-secret");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private String loginAdmin() throws Exception {
        var result = mockMvc.perform(post("/api/admin/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "admin",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return AuthTestTokens.adminJwtFrom(result);
    }

    private Long createVendorAccount(String adminToken, String baseUrl) throws Exception {
        return createVendorAccount(adminToken, "openai_gateway", "Discovery Gateway", baseUrl);
    }

    private Long createVendorAccount(String adminToken, String vendorCode, String accountName, String baseUrl) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/model-vendor-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorCode": "%s",
                                  "accountName": "%s",
                                  "baseUrl": "%s",
                                  "apiKey": "discovery-secret",
                                  "balanceQueryMode": "MANUAL",
                                  "enabled": true
                                }
                                """.formatted(vendorCode, accountName, baseUrl)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
