package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AgentServiceClient agentServiceClient;

    @Test
    void ofoxAccountUsesEnabledLinkedGenericMediaModel() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "openai/gpt-image-2", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            String baseUrl = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            Long accountId = createVendorAccount(adminToken, "ofox", "oFox Gateway", baseUrl);
            bindEnabledModel(
                    accountId,
                    "ofox-generic-gateway-account-probe",
                    "openai_images_gateway",
                    "openai/gpt-image-2",
                    baseUrl,
                    "[\"IMAGE_GENERATION\"]"
            );

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(true))
                    .andExpect(jsonPath("$.data.provider").value("openai_images_gateway"))
                    .andExpect(jsonPath("$.data.modelName").value("openai/gpt-image-2"))
                    .andExpect(jsonPath("$.data.account.healthStatus").value("OK"));
            Mockito.verify(agentServiceClient, Mockito.never()).testModelConfig(any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agnesAccountUsesModelsProbeWithoutChangingLinkedModelState() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "agnes-2.0-flash", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            Long accountId = createVendorAccount(adminToken, "agnes", "Agnes account probe",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()));
            jdbcTemplate.update("""
                    INSERT INTO agent_model_configs(vendor_account_id, display_name, config_code, provider, model_name,
                                                    base_url, api_key, billing_unit, capabilities,
                                                    enabled, agent_enabled, last_test_success)
                    VALUES(?, 'Agnes Image', 'agnes-account-probe-model', 'agnes_images', 'agnes-image-2.1-flash',
                           '', '', 'IMAGE_TOKEN', '["IMAGE_GENERATION"]',
                           0, 0, 0)
                    """, accountId);
            Long modelId = jdbcTemplate.queryForObject(
                    "SELECT id FROM agent_model_configs WHERE config_code='agnes-account-probe-model'",
                    Long.class);

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(true))
                    .andExpect(jsonPath("$.data.provider").value("agnes"))
                    .andExpect(jsonPath("$.data.modelName").value(""))
                    .andExpect(jsonPath("$.data.account.healthStatus").value("OK"));

            Boolean enabled = jdbcTemplate.queryForObject(
                    "SELECT enabled FROM agent_model_configs WHERE id=?", Boolean.class, modelId);
            Boolean lastTestSuccess = jdbcTemplate.queryForObject(
                    "SELECT last_test_success FROM agent_model_configs WHERE id=?", Boolean.class, modelId);
            assertThat(enabled).isFalse();
            assertThat(lastTestSuccess).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void minimaxAccountWithoutEnabledModelFailsWithoutExternalRequest() throws Exception {
        String adminToken = loginAdmin();
        Long accountId = createVendorAccount(
                adminToken,
                "minimax",
                "Unbound MiniMax account",
                "http://127.0.0.1:1/v1"
        );

        mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("绑定并启用模型")))
                .andExpect(jsonPath("$.data.provider").value(""))
                .andExpect(jsonPath("$.data.modelName").value(""))
                .andExpect(jsonPath("$.data.account.healthStatus").value("ERROR"));

        Mockito.verify(agentServiceClient, Mockito.never()).testModelConfig(any());
    }

    @Test
    void minimaxAccountUsesEnabledLinkedModelThroughAgentService() throws Exception {
        Mockito.when(agentServiceClient.testModelConfig(any()))
                .thenAnswer(invocation -> {
                    var request = invocation.getArgument(0, com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest.class);
                    return new AgentModelConfigTestResponse(
                            true,
                            request.provider(),
                            request.modelName(),
                            8L,
                            "ok",
                            "pong"
                    );
                });
        String adminToken = loginAdmin();
        String baseUrl = "http://127.0.0.1:1/v1";
        Long accountId = createVendorAccount(adminToken, "minimax", "MiniMax linked model", baseUrl);
        bindEnabledModel(
                accountId,
                "minimax-account-probe",
                "minimax",
                "MiniMax-M2.7",
                baseUrl,
                "[\"TEXT_GENERATION\"]"
        );

        mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("minimax"))
                .andExpect(jsonPath("$.data.modelName").value("MiniMax-M2.7"))
                .andExpect(jsonPath("$.data.account.healthStatus").value("OK"));

        Mockito.verify(agentServiceClient).testModelConfig(argThat(request ->
                request != null
                        && "minimax".equals(request.provider())
                        && "MiniMax-M2.7".equals(request.modelName())
                        && baseUrl.equals(request.baseUrl())
                        && "discovery-secret".equals(request.apiKey())));
    }

    @Test
    void accountProbeReportsCredentialFailureForUnauthorizedOfoxGateway() throws Exception {
        HttpServer server = statusServer(401, "{\"error\":\"unauthorized\"}");
        try {
            String adminToken = loginAdmin();
            String baseUrl = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            Long accountId = createVendorAccount(adminToken, "ofox", "Unauthorized oFox Gateway", baseUrl);
            bindEnabledModel(
                    accountId,
                    "unauthorized-ofox-gateway-probe",
                    "openai_images_gateway",
                    "openai/gpt-image-2",
                    baseUrl,
                    "[\"IMAGE_GENERATION\"]"
            );

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(false))
                    .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("API Key 无效")))
                    .andExpect(jsonPath("$.data.provider").value("openai_images_gateway"))
                    .andExpect(jsonPath("$.data.modelName").value("openai/gpt-image-2"))
                    .andExpect(jsonPath("$.data.account.healthStatus").value("ERROR"));
            Mockito.verify(agentServiceClient, Mockito.never()).testModelConfig(any());
        } finally {
            server.stop(0);
        }
    }

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

            jdbcTemplate.update("""
                    UPDATE agent_model_configs
                    SET enabled = 0
                    WHERE vendor_account_id = ? AND model_name = 'gpt-4o-mini'
                    """, accountId);

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.imported").value(0))
                    .andExpect(jsonPath("$.data.updated").value(3));

            Boolean rediscoveredEnabled = jdbcTemplate.queryForObject(
                    "SELECT enabled FROM agent_model_configs WHERE vendor_account_id = ? AND model_name = 'gpt-4o-mini'",
                    Boolean.class,
                    accountId
            );
            assertThat(rediscoveredEnabled).isTrue();

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

    @Test
    void adminCanDiscoverQwen36PlusAsVisionAgentModel() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "qwen3.6-plus", "object": "model"},
                    {"id": "qwen-plus", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            Long accountId = createVendorAccount(
                    adminToken,
                    "qwen",
                    "Bailian Qwen",
                    "http://127.0.0.1:%d/compatible-mode/v1".formatted(server.getAddress().getPort())
            );

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.imported").value(2))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='qwen3.6-plus')].provider").value("qwen"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='qwen3.6-plus')].capabilities[0]").value("TEXT_GENERATION"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='qwen3.6-plus')].capabilities[1]").value("VISION_INPUT"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='qwen-plus')].provider").value("qwen"))
                    .andExpect(jsonPath("$.data.models[?(@.modelName=='qwen-plus')].capabilities[0]").value("TEXT_GENERATION"));

            AgentModelConfig vision = agentModelConfigMapper.findActiveByVendorAccountAndModelName(accountId, "qwen3.6-plus");
            assertThat(vision.getProvider()).isEqualTo("qwen");
            assertThat(vision.getCapabilities()).contains("TEXT_GENERATION", "VISION_INPUT");
            assertThat(vision.getDocsUrl()).contains("compatibility-of-openai-with-dashscope");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoveryHonorsDatabaseCapabilityOverrides() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "qwen3.6-plus-metadata-override", "object": "model"}
                  ]
                }
                """);
        String originalCapabilities = jdbcTemplate.queryForObject(
                "SELECT capabilities_json FROM model_provider_metadata WHERE provider_code = 'qwen'",
                String.class
        );
        try {
            jdbcTemplate.update(
                    "UPDATE model_provider_metadata SET capabilities_json = ? WHERE provider_code = 'qwen'",
                    "[\"TEXT_GENERATION\"]"
            );
            String adminToken = loginAdmin();
            Long accountId = createVendorAccount(
                    adminToken,
                    "qwen",
                    "Bailian metadata override",
                    "http://127.0.0.1:%d/compatible-mode/v1".formatted(server.getAddress().getPort())
            );

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.imported").value(1))
                    .andExpect(jsonPath("$.data.skipped").value(0))
                    .andExpect(jsonPath("$.data.models[0].provider").value("qwen"))
                    .andExpect(jsonPath("$.data.models[0].capabilities[0]").value("TEXT_GENERATION"))
                    .andExpect(jsonPath("$.data.models[0].capabilities[1]").doesNotExist());

            AgentModelConfig model = agentModelConfigMapper.findActiveByVendorAccountAndModelName(
                    accountId,
                    "qwen3.6-plus-metadata-override"
            );
            assertThat(model.getCapabilities()).isEqualTo("[\"TEXT_GENERATION\"]");
        } finally {
            jdbcTemplate.update(
                    "UPDATE model_provider_metadata SET capabilities_json = ? WHERE provider_code = 'qwen'",
                    originalCapabilities
            );
            server.stop(0);
        }
    }

    @Test
    void adminCanDiscoverVolcengineSeedreamWithJsonImageInputDefaults() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "doubao-seedream-4-5-251128", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            Long accountId = createVendorAccount(
                    adminToken,
                    "volcengine",
                    "Volcengine Images",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort())
            );

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.imported").value(1))
                    .andExpect(jsonPath("$.data.models[0].provider").value("volcengine_images"));

            AgentModelConfig image = agentModelConfigMapper.findActiveByVendorAccountAndModelName(accountId, "doubao-seedream-4-5-251128");
            assertThat(image.getExtraAuthJson()).contains("jsonImageArray", "/images/generations", "responseFormat");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoveryPersistsOnlyCapabilitiesDeclaredByTheSelectedProviderContract() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "doubao-seed-2.0-lite", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            Long accountId = createVendorAccount(
                    adminToken,
                    "volcengine",
                    "Volcengine Chat",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort())
            );

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.imported").value(1))
                    .andExpect(jsonPath("$.data.models[0].provider").value("openai_compatible"))
                    .andExpect(jsonPath("$.data.models[0].capabilities[0]").value("TEXT_GENERATION"))
                    .andExpect(jsonPath("$.data.models[0].capabilities[1]").value("VISION_INPUT"))
                    .andExpect(jsonPath("$.data.models[0].capabilities[2]").doesNotExist());

            AgentModelConfig chat = agentModelConfigMapper.findActiveByVendorAccountAndModelName(accountId, "doubao-seed-2.0-lite");
            assertThat(chat.getProvider()).isEqualTo("openai_compatible");
            assertThat(chat.getCapabilities()).isEqualTo("[\"TEXT_GENERATION\",\"VISION_INPUT\"]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void adminCanDeleteVendorAndCascadeBoundToolsModelsAndAccounts() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "gpt-4o-mini", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            mockMvc.perform(put("/api/admin/v1/model-vendors")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "vendorCode": "cascade_test_vendor",
                                      "vendorLabel": "Cascade Test Vendor",
                                      "iconAsset": "api",
                                      "sortOrder": 10,
                                      "enabled": true
                                    }
                                    """))
                    .andExpect(status().isOk());
            Long accountId = createVendorAccount(
                    adminToken,
                    "cascade_test_vendor",
                    "Cascade Test Account",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort())
            );

            mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/discover-models", accountId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
            AgentModelConfig config = agentModelConfigMapper.findActiveByVendorAccountAndModelName(accountId, "gpt-4o-mini");
            assertThat(config).isNotNull();
            jdbcTemplate.update("""
                    INSERT INTO ai_tools(tool_code, tool_name, category_id, description, tool_type, input_modality,
                                         output_modality, status, estimated_credit_cost, model_config_id, created_by,
                                         updated_by, is_deleted)
                    VALUES('cascade_delete_tool', 'Cascade Delete Tool', NULL, 'test', 'TEXT_GENERATION', 'TEXT',
                           'TEXT', 'ONLINE', 1, ?, 1, 1, 0)
                    """, config.getId());

            mockMvc.perform(delete("/api/admin/v1/model-vendors/{vendorCode}", "cascade_test_vendor")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());

            Integer activeTools = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM ai_tools WHERE tool_code='cascade_delete_tool' AND is_deleted=0",
                    Integer.class);
            Integer activeModels = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM agent_model_configs WHERE vendor_account_id=? AND COALESCE(is_deleted,0)=0",
                    Integer.class,
                    accountId);
            Integer activeAccounts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM model_vendor_accounts WHERE vendor_code='cascade_test_vendor' AND COALESCE(is_deleted,0)=0",
                    Integer.class);
            Integer enabledVendors = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM model_vendors WHERE vendor_code='cascade_test_vendor' AND enabled=1",
                    Integer.class);
            assertThat(activeTools).isZero();
            assertThat(activeModels).isZero();
            assertThat(activeAccounts).isZero();
            assertThat(enabledVendors).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptOnlyAccountTestRequiresRealCredentialNotJustExtraAuthSettings() throws Exception {
        String adminToken = loginAdmin();
        String response = mockMvc.perform(post("/api/admin/v1/model-vendor-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorCode": "local_media_mock",
                                  "accountName": "No Credential Media",
                                  "baseUrl": "http://127.0.0.1:1/v1",
                                  "extraAuthJson": "{\\"responseFormat\\":\\"url\\"}",
                                  "balanceQueryMode": "MANUAL",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long accountId = Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        bindEnabledModel(
                accountId,
                "local-media-mock-account-probe",
                "local_media_mock",
                "local-media-mock",
                "http://127.0.0.1:1/v1",
                "[\"IMAGE_GENERATION\"]"
        );

        mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.account.healthStatus").value("ERROR"));
    }

    @Test
    void modelTestByIdPersistsFailureWhenConnectivityCheckThrows() throws Exception {
        String adminToken = loginAdmin();
        String response = mockMvc.perform(post("/api/admin/v1/model-vendor-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorCode": "ofox",
                                  "accountName": "Broken oFox Gateway",
                                  "baseUrl": "http://127.0.0.1:1/v1",
                                  "extraAuthJson": "{\\"responseFormat\\":\\"url\\"}",
                                  "balanceQueryMode": "MANUAL",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long accountId = Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(vendor_account_id, display_name, config_code, provider, model_name,
                                                base_url, api_key, extra_auth_json, billing_unit, capabilities,
                                                enabled, agent_enabled, last_test_success)
                VALUES(?, 'Broken Chat', 'broken-chat-test', 'openai_compatible', 'broken-chat',
                       'http://127.0.0.1:1/v1', '', NULL, 'TOKEN_PER_M', 'TEXT_GENERATION',
                       1, 0, 1)
                """, accountId);
        Long modelId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_model_configs WHERE config_code='broken-chat-test'",
                Long.class);

        mockMvc.perform(post("/api/admin/v1/agent/model-config/{id}/test", modelId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false));

        Boolean lastTestSuccess = jdbcTemplate.queryForObject(
                "SELECT last_test_success FROM agent_model_configs WHERE id=?",
                Boolean.class,
                modelId);
        assertThat(lastTestSuccess).isFalse();
    }

    @Test
    void imageModelTestChecksModelNameWithoutMarkingAccountUnhealthy() throws Exception {
        HttpServer server = modelsServer("""
                {
                  "object": "list",
                  "data": [
                    {"id": "another-image-model", "object": "model"}
                  ]
                }
                """);
        try {
            String adminToken = loginAdmin();
            Long accountId = createVendorAccount(adminToken, "ofox", "oFox Image Gateway",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()));
            jdbcTemplate.update("UPDATE model_vendor_accounts SET health_status='OK' WHERE id=?", accountId);
            jdbcTemplate.update("""
                    INSERT INTO agent_model_configs(vendor_account_id, display_name, config_code, provider, model_name,
                                                    base_url, api_key, extra_auth_json, billing_unit, capabilities,
                                                    enabled, agent_enabled, last_test_success)
                    VALUES(?, 'GPT Image 2', 'image-model-name-miss', 'openai_images_gateway', 'openai/gpt-image-2',
                           '', '', NULL, 'IMAGE_TOKEN', '["IMAGE_GENERATION"]',
                           1, 0, 1)
                    """, accountId);
            Long modelId = jdbcTemplate.queryForObject(
                    "SELECT id FROM agent_model_configs WHERE config_code='image-model-name-miss'",
                    Long.class);

            mockMvc.perform(post("/api/admin/v1/agent/model-config/{id}/test", modelId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(false))
                    .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("不包含 openai/gpt-image-2")));

            Boolean lastTestSuccess = jdbcTemplate.queryForObject(
                    "SELECT last_test_success FROM agent_model_configs WHERE id=?",
                    Boolean.class,
                    modelId);
            String accountHealth = jdbcTemplate.queryForObject(
                    "SELECT health_status FROM model_vendor_accounts WHERE id=?",
                    String.class,
                    accountId);
            assertThat(lastTestSuccess).isFalse();
            assertThat(accountHealth).isEqualTo("OK");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer modelsServer(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.function.Consumer<com.sun.net.httpserver.HttpExchange> handler = exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer discovery-secret");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        };
        server.createContext("/v1/models", handler::accept);
        server.createContext("/compatible-mode/v1/models", handler::accept);
        server.start();
        return server;
    }

    private HttpServer statusServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.function.Consumer<com.sun.net.httpserver.HttpExchange> handler = exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer discovery-secret");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        };
        server.createContext("/v1/models", handler::accept);
        server.createContext("/models", handler::accept);
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
        return createVendorAccount(adminToken, "ofox", "oFox Discovery Gateway", baseUrl);
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

    private void bindEnabledModel(Long accountId,
                                  String configCode,
                                  String provider,
                                  String modelName,
                                  String baseUrl,
                                  String capabilities) {
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(vendor_account_id, display_name, config_code, provider, model_name,
                                                base_url, api_key, billing_unit, capabilities,
                                                enabled, agent_enabled, is_default, is_deleted)
                VALUES(?, ?, ?, ?, ?, ?, '', 'PER_CALL', ?, 1, 0, 1, 0)
                """,
                accountId,
                modelName,
                configCode,
                provider,
                modelName,
                baseUrl,
                capabilities);
    }
}
