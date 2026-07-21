package com.aiminilab.aitoolmarket.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_configuration_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AdminConfigurationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void adminCanLoadUnifiedApiOverview() throws Exception {
        String adminToken = loginAdmin();
        mockMvc.perform(get("/api/admin/v1/unified-api/overview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").exists())
                .andExpect(jsonPath("$.data.vendors").isArray());
    }

    @Test
    void adminCanCreateUpdateDisableCategoriesAndPersistSettings() throws Exception {
        String adminToken = loginAdmin();

        String categoryResponse = mockMvc.perform(post("/api/admin/v1/tool-categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryCode": "ops",
                                  "categoryName": "Operations",
                                  "sortOrder": 7,
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryCode").value("ops"))
                .andExpect(jsonPath("$.data.categoryName").value("Operations"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long categoryId = Long.parseLong(categoryResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(put("/api/admin/v1/tool-categories/{categoryId}", categoryId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryCode": "ops",
                                  "categoryName": "Growth Ops",
                                  "sortOrder": 3,
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryName").value("Growth Ops"))
                .andExpect(jsonPath("$.data.sortOrder").value(3));

        mockMvc.perform(patch("/api/admin/v1/tool-categories/{categoryId}/status", categoryId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DISABLED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(get("/api/admin/v1/tool-categories")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.categoryCode=='ops')].status").value("DISABLED"));

        mockMvc.perform(put("/api/admin/v1/settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "settings": {
                                    "platform.name": "AI Tool Market",
                                    "credits.signupGrant": "120"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['platform.name']").value("AI Tool Market"))
                .andExpect(jsonPath("$.data['credits.signupGrant']").value("120"));

        mockMvc.perform(get("/api/admin/v1/settings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['platform.name']").value("AI Tool Market"));
    }

    @Test
    void adminCanManageProxyConfigWithoutReadingSubscriptionSecret() throws Exception {
        String adminToken = loginAdmin();

        mockMvc.perform(put("/api/admin/v1/proxy-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "sourceType": "SUBSCRIPTION",
                                  "displayName": "Overseas primary",
                                  "subscriptionUrl": "https://example.com/subscribe?token=top-secret",
                                  "subscriptionUpdateIntervalMinutes": 360,
                                  "mihomoEndpoint": "http://host.docker.internal:7890",
                                  "manualProtocol": "HTTP",
                                  "manualHost": "",
                                  "manualPort": 7890,
                                  "manualUsername": "",
                                  "manualPassword": "",
                                  "noProxyHosts": "localhost,backend"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.sourceType").value("SUBSCRIPTION"))
                .andExpect(jsonPath("$.data.subscriptionConfigured").value(true))
                .andExpect(jsonPath("$.data.subscriptionUrlMasked").value("https://example.com/***?token=***"))
                .andExpect(jsonPath("$.data.proxyUrlMasked").value("http://mihomo:7890"));

        mockMvc.perform(get("/api/admin/v1/proxy-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Overseas primary"))
                .andExpect(jsonPath("$.data.subscriptionUrl").doesNotExist())
                .andExpect(jsonPath("$.data.subscriptionUrlMasked").value("https://example.com/***?token=***"));

        mockMvc.perform(get("/api/admin/v1/proxy-config/runtime")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.managed").value(false))
                .andExpect(jsonPath("$.data.available").value(false));

        mockMvc.perform(post("/api/admin/v1/proxy-config/apply")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.managed").value(false));
    }

    @Test
    void adminSettingPromptVersionsCanBeQueriedAndRestored() throws Exception {
        String adminToken = loginAdmin();

        mockMvc.perform(put("/api/admin/v1/settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "settings": {
                                    "agent.system_prompt": "custom prompt for test"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['agent.system_prompt']").value("custom prompt for test"));

        mockMvc.perform(get("/api/admin/v1/settings/{key}/versions", "agent.system_prompt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].settingKey").value("agent.system_prompt"))
                .andExpect(jsonPath("$.data[0].settingValue").value("custom prompt for test"));

        mockMvc.perform(post("/api/admin/v1/settings/{key}/restore-default", "agent.system_prompt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['agent.system_prompt']").value(org.hamcrest.Matchers.not("custom prompt for test")));
    }

    @Test
    void adminCanConfigureAgentRouterSettings() throws Exception {
        String adminToken = loginAdmin();

        mockMvc.perform(put("/api/admin/v1/settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "settings": {
                                    "agent.router.enabled": "true",
                                    "agent.router.prompt": "router prompt for test",
                                    "agent.router.min_confidence": "0.8",
                                    "agent.router.fallback_to_rules": "true"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['agent.router.prompt']").value("router prompt for test"))
                .andExpect(jsonPath("$.data['agent.router.min_confidence']").value("0.8"));

        mockMvc.perform(get("/api/admin/v1/settings/{key}/versions", "agent.router.prompt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].settingKey").value("agent.router.prompt"))
                .andExpect(jsonPath("$.data[0].settingValue").value("router prompt for test"));

        mockMvc.perform(post("/api/admin/v1/settings/{key}/restore-default", "agent.router.prompt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['agent.router.prompt']").value(org.hamcrest.Matchers.not("router prompt for test")));
    }

    @Test
    void adminCanUploadCustomerServiceQrCode() throws Exception {
        String adminToken = loginAdmin();
        byte[] png = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "service-qr.png",
                "image/png",
                png
        );

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/admin/v1/settings/customer-service/qr-upload")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("/generated/customer-service/")))
                .andExpect(jsonPath("$.data.filename").isNotEmpty());

        mockMvc.perform(get("/api/admin/v1/settings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['customerService.qrCodeUrl']").value(org.hamcrest.Matchers.startsWith("/generated/customer-service/")));
    }

    @Test
    void configBundleExportRedactsSecretsAndImportPreservesExistingSecrets() throws Exception {
        String adminToken = loginAdmin();

        mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Secret Model",
                                  "configCode": "secret_model",
                                  "provider": "mock",
                                  "modelName": "mock",
                                  "apiKey": "sk-secret-value",
                                  "extraAuthJson": "{\\"secretKey\\":\\"real-secret\\"}",
                                  "timeoutSeconds": 60,
                                  "connectTimeoutSeconds": 30,
                                  "readTimeoutSeconds": 300,
                                  "enabled": true,
                                  "isDefault": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value("sk***ue"))
                .andExpect(jsonPath("$.data.extraAuthJsonMasked").value("********"));

        mockMvc.perform(get("/api/admin/v1/config-bundles/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.format").value("ai-tool-market-config-bundle"))
                .andExpect(jsonPath("$.data.secretsRedacted").value(true))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].apiKey").value(""))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].extraAuthJson").value(""))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].connectTimeoutSeconds").value(30))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].readTimeoutSeconds").value(300));

        mockMvc.perform(get("/api/admin/v1/config-bundles/export?includeSecrets=true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secretsRedacted").value(false))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].secretsRedacted").value(false))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].apiKey").value("sk-secret-value"))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].extraAuthJson").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("\"secretKey\":\"real-secret\""))))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].extraAuthJson").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("\"connectTimeoutSeconds\":30"))))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='secret_model')].extraAuthJson").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("\"readTimeoutSeconds\":300"))));

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 1,
                                  "secretsRedacted": true,
                                  "settings": {},
                                  "categories": [],
                                  "tools": [],
                                  "modelConfigs": [
                                    {
                                      "displayName": "Imported Name",
                                      "configCode": "secret_model",
                                      "provider": "mock",
                                      "modelName": "mock",
                                      "apiKey": "",
                                      "extraAuthJson": "",
                                      "timeoutSeconds": 60,
                                      "connectTimeoutSeconds": 45,
                                      "readTimeoutSeconds": 600,
                                      "enabled": true,
                                      "isDefault": true
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelConfigs").value(1));

        mockMvc.perform(get("/api/admin/v1/agent/model-config/list")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.configCode=='secret_model')].displayName").value("Imported Name"))
                .andExpect(jsonPath("$.data[?(@.configCode=='secret_model')].connectTimeoutSeconds").value(45))
                .andExpect(jsonPath("$.data[?(@.configCode=='secret_model')].readTimeoutSeconds").value(600))
                .andExpect(jsonPath("$.data[?(@.configCode=='secret_model')].apiKeyMasked").value("sk***ue"))
                .andExpect(jsonPath("$.data[?(@.configCode=='secret_model')].extraAuthJsonMasked").value("********"));
    }

    @Test
    void configBundleRoutingVersionKeepsLegacyStateAndHonorsV2ExplicitClear() throws Exception {
        String adminToken = loginAdmin();
        String existingAccountName = "legacy-routing-v1-existing";
        String newAccountName = "legacy-routing-v1-new";
        String existingConfigCode = "legacy_routing_v1_existing_model";
        String newConfigCode = "legacy_routing_v1_new_model";

        jdbcTemplate.update("""
                INSERT INTO model_account_routing_pools(vendor_code, pool_name, pool_key)
                VALUES ('mock', 'Legacy Compatibility Pool', 'legacy compatibility pool')
                """);
        Long routingPoolId = jdbcTemplate.queryForObject(
                "SELECT id FROM model_account_routing_pools WHERE vendor_code = 'mock' AND pool_key = 'legacy compatibility pool'",
                Long.class
        );
        jdbcTemplate.update("""
                INSERT INTO model_vendor_accounts(
                    vendor_code, account_name, base_url, api_key,
                    balance_query_mode, balance_status, health_status,
                    load_balance_enabled, load_balance_weight, routing_pool_id,
                    enabled, is_deleted
                ) VALUES ('mock', ?, 'https://legacy-routing-existing.example/v1', 'existing-secret',
                          'MANUAL', 'UNKNOWN', 'UNKNOWN', 1, 37, ?, 1, 0)
                """, existingAccountName, routingPoolId);
        Long existingAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM model_vendor_accounts WHERE vendor_code = 'mock' AND account_name = ?",
                Long.class,
                existingAccountName
        );
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                    vendor_account_id, routing_pool_id, display_name, config_code,
                    provider, model_name, base_url, api_key, capabilities,
                    enabled, agent_enabled, is_default, is_deleted
                ) VALUES (?, ?, 'Legacy routing model', ?, 'mock', 'mock',
                          'https://legacy-routing-existing.example/v1', '', '["TEXT_GENERATION"]',
                          1, 1, 0, 0)
                """, existingAccountId, routingPoolId, existingConfigCode);

        mockMvc.perform(get("/api/admin/v1/config-bundles/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 1,
                                  "exportScope": "SELECTED_TOOLS",
                                  "secretsRedacted": false,
                                  "settings": {},
                                  "vendorAccounts": [
                                    {
                                      "vendorCode": "mock",
                                      "accountName": "legacy-routing-v1-existing",
                                      "accountRef": "mock::legacy-routing-v1-existing",
                                      "baseUrl": "https://legacy-routing-existing.example/v1",
                                      "secretsRedacted": true,
                                      "balanceQueryMode": "MANUAL",
                                      "enabled": true
                                    },
                                    {
                                      "vendorCode": "mock",
                                      "accountName": "legacy-routing-v1-new",
                                      "accountRef": "mock::legacy-routing-v1-new",
                                      "baseUrl": "https://legacy-routing-new.example/v1",
                                      "apiKey": "new-secret",
                                      "secretsRedacted": false,
                                      "balanceQueryMode": "MANUAL",
                                      "enabled": true
                                    }
                                  ],
                                  "modelConfigs": [
                                    {
                                      "displayName": "Legacy routing model updated",
                                      "configCode": "legacy_routing_v1_existing_model",
                                      "vendorAccountRef": "mock::legacy-routing-v1-existing",
                                      "provider": "mock",
                                      "modelName": "mock",
                                      "secretsRedacted": true,
                                      "enabled": true,
                                      "agentEnabled": true,
                                      "isDefault": false,
                                      "capabilities": ["TEXT_GENERATION"]
                                    },
                                    {
                                      "displayName": "Legacy routing new model",
                                      "configCode": "legacy_routing_v1_new_model",
                                      "vendorAccountRef": "mock::legacy-routing-v1-new",
                                      "provider": "mock",
                                      "modelName": "mock",
                                      "secretsRedacted": true,
                                      "enabled": true,
                                      "agentEnabled": true,
                                      "isDefault": false,
                                      "capabilities": ["TEXT_GENERATION"]
                                    }
                                  ],
                                  "categories": [],
                                  "tools": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vendorAccounts").value(2))
                .andExpect(jsonPath("$.data.modelConfigs").value(2));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT load_balance_enabled FROM model_vendor_accounts WHERE id = ?",
                Integer.class,
                existingAccountId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT load_balance_weight FROM model_vendor_accounts WHERE id = ?",
                Integer.class,
                existingAccountId
        )).isEqualTo(37);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT routing_pool_id FROM model_vendor_accounts WHERE id = ?",
                Long.class,
                existingAccountId
        )).isEqualTo(routingPoolId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT routing_pool_id FROM agent_model_configs WHERE config_code = ?",
                Long.class,
                existingConfigCode
        )).isEqualTo(routingPoolId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT load_balance_enabled FROM model_vendor_accounts WHERE vendor_code = 'mock' AND account_name = ?",
                Integer.class,
                newAccountName
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT routing_pool_id FROM model_vendor_accounts WHERE vendor_code = 'mock' AND account_name = ?",
                Long.class,
                newAccountName
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT routing_pool_id FROM agent_model_configs WHERE config_code = ?",
                Long.class,
                newConfigCode
        )).isNull();

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 2,
                                  "exportScope": "SELECTED_TOOLS",
                                  "secretsRedacted": true,
                                  "settings": {},
                                  "vendorAccounts": [
                                    {
                                      "vendorCode": "mock",
                                      "accountName": "legacy-routing-v1-existing",
                                      "accountRef": "mock::legacy-routing-v1-existing",
                                      "baseUrl": "https://legacy-routing-existing.example/v1",
                                      "secretsRedacted": true,
                                      "balanceQueryMode": "MANUAL",
                                      "routingPoolName": "Legacy Compatibility Pool",
                                      "loadBalanceEnabled": true,
                                      "loadBalanceWeight": 37,
                                      "enabled": true
                                    }
                                  ],
                                  "modelConfigs": [
                                    {
                                      "displayName": "Legacy routing model updated",
                                      "configCode": "legacy_routing_v1_existing_model",
                                      "vendorAccountRef": "mock::legacy-routing-v1-existing",
                                      "routingPoolName": null,
                                      "provider": "mock",
                                      "modelName": "mock",
                                      "secretsRedacted": true,
                                      "enabled": true,
                                      "agentEnabled": true,
                                      "isDefault": false,
                                      "capabilities": ["TEXT_GENERATION"]
                                    }
                                  ],
                                  "categories": [],
                                  "tools": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelConfigs").value(1));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT routing_pool_id FROM agent_model_configs WHERE config_code = ?",
                Long.class,
                existingConfigCode
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT routing_pool_id FROM model_vendor_accounts WHERE id = ?",
                Long.class,
                existingAccountId
        )).isEqualTo(routingPoolId);

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 2,
                                  "exportScope": "SELECTED_TOOLS",
                                  "secretsRedacted": true,
                                  "settings": {},
                                  "vendorAccounts": [
                                    {
                                      "vendorCode": "mock",
                                      "accountName": "legacy-routing-v1-existing",
                                      "accountRef": "mock::legacy-routing-v1-existing",
                                      "baseUrl": "https://legacy-routing-existing.example/v1",
                                      "secretsRedacted": true,
                                      "balanceQueryMode": "MANUAL",
                                      "routingPoolName": null,
                                      "loadBalanceEnabled": false,
                                      "loadBalanceWeight": 61,
                                      "enabled": true
                                    }
                                  ],
                                  "modelConfigs": [],
                                  "categories": [],
                                  "tools": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vendorAccounts").value(1));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT load_balance_enabled FROM model_vendor_accounts WHERE id = ?",
                Integer.class,
                existingAccountId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT load_balance_weight FROM model_vendor_accounts WHERE id = ?",
                Integer.class,
                existingAccountId
        )).isEqualTo(61);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT routing_pool_id FROM model_vendor_accounts WHERE id = ?",
                Long.class,
                existingAccountId
        )).isNull();
    }

    @Test
    void configBundleImportReusesEquivalentModelAndKeepsToolBinding() throws Exception {
        String adminToken = loginAdmin();

        String modelResponse = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Existing Z Image",
                                  "configCode": "siliconflow_image_turbo",
                                  "provider": "siliconflow_images",
                                  "modelName": "Tongyi-MAI/Z-Image-Turbo",
                                  "baseUrl": "https://api.siliconflow.cn",
                                  "timeoutSeconds": 60,
                                  "enabled": true,
                                  "isDefault": false,
                                  "capabilities": ["IMAGE_GENERATION"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long existingModelId = Long.parseLong(modelResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 1,
                                  "secretsRedacted": true,
                                  "settings": {},
                                  "modelConfigs": [
                                    {
                                      "displayName": "Imported Z Image",
                                      "configCode": "model_99",
                                      "provider": "siliconflow_images",
                                      "modelName": "Tongyi-MAI/Z-Image-Turbo",
                                      "baseUrl": "https://api.siliconflow.cn",
                                      "apiKey": "",
                                      "extraAuthJson": "",
                                      "timeoutSeconds": 60,
                                      "enabled": true,
                                      "isDefault": false,
                                      "capabilities": ["IMAGE_GENERATION"]
                                    }
                                  ],
                                  "categories": [
                                    {
                                      "categoryCode": "import_test",
                                      "categoryName": "Import Test",
                                      "sortOrder": 1,
                                      "status": "ACTIVE"
                                    }
                                  ],
                                  "tools": [
                                    {
                                      "toolCode": "dup_tool",
                                      "toolName": "Duplicate Tool",
                                      "categoryCode": "import_test",
                                      "toolType": "IMAGE_GENERATION",
                                      "inputModality": "TEXT",
                                      "outputModality": "IMAGE",
                                      "status": "ONLINE",
                                      "estimatedCreditCost": 1,
                                      "modelConfigCode": "model_99",
                                      "executionHandler": "IMAGE_GENERATION",
                                      "agentEnabled": false,
                                      "fields": [],
                                      "prompts": []
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelConfigs").value(0))
                .andExpect(jsonPath("$.data.tools").value(1))
                .andExpect(jsonPath("$.data.warnings[0]").value("Reused existing model config siliconflow_image_turbo for imported model config model_99"))
                .andExpect(jsonPath("$.data.warnings").value(hasItem("Tool dup_tool kept as draft because it could not be published: bound model config has no API key: Existing Z Image")));

        mockMvc.perform(get("/api/admin/v1/agent/model-config/list")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.provider=='siliconflow_images' && @.modelName=='Tongyi-MAI/Z-Image-Turbo')]").isArray())
                .andExpect(jsonPath("$.data[?(@.configCode=='model_99')]").isEmpty());

        mockMvc.perform(get("/api/admin/v1/tools?page=1&pageSize=50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[?(@.toolCode=='dup_tool')].modelConfigId").value(existingModelId.intValue()))
                .andExpect(jsonPath("$.data.list[?(@.toolCode=='dup_tool')].executionHandler").value("IMAGE_GENERATION"))
                .andExpect(jsonPath("$.data.list[?(@.toolCode=='dup_tool')].status").value("DRAFT"));

        mockMvc.perform(get("/api/admin/v1/config-bundles/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools[?(@.toolCode=='dup_tool')].executionHandler").value("IMAGE_GENERATION"))
                .andExpect(jsonPath("$.data.tools[?(@.toolCode=='dup_tool')].agentEnabled").value(false));
    }

    @Test
    void configBundleImportDeduplicatesVendorAccountsAndDropsNonAuthMetadata() throws Exception {
        String adminToken = loginAdmin();

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 1,
                                  "secretsRedacted": false,
                                  "settings": {},
                                  "vendorAccounts": [
                                    {
                                      "vendorCode": "moonshot",
                                      "accountName": "账户A",
                                      "accountRef": "moonshot::a",
                                      "baseUrl": "https://api.moonshot.cn/v1/",
                                      "apiKey": "sk-moonshot",
                                      "extraAuthJson": "{\\"pricingVerifiedAt\\":\\"2026-05-25\\",\\"pricingNote\\":\\"ignored\\",\\"costAffectingParams\\":\\"model;messages\\"}",
                                      "secretsRedacted": false,
                                      "balanceQueryMode": "MANUAL",
                                      "enabled": true
                                    },
                                    {
                                      "vendorCode": "moonshot",
                                      "accountName": "账户B",
                                      "accountRef": "moonshot::b",
                                      "baseUrl": "https://api.moonshot.cn/v1",
                                      "apiKey": "sk-moonshot",
                                      "extraAuthJson": "{\\"pricingVerifiedAt\\":\\"2026-05-25\\"}",
                                      "secretsRedacted": false,
                                      "balanceQueryMode": "MANUAL",
                                      "enabled": true
                                    },
                                    {
                                      "vendorCode": "mineru",
                                      "accountName": "MinerU",
                                      "accountRef": "mineru::default",
                                      "baseUrl": "https://mineru.net",
                                      "apiKey": "mineru-token",
                                      "secretsRedacted": false,
                                      "balanceQueryMode": "MANUAL",
                                      "enabled": true
                                    }
                                  ],
                                  "modelConfigs": [
                                    {
                                      "displayName": "Kimi",
                                      "configCode": "test_kimi_import_cleanup",
                                      "vendorAccountRef": "moonshot::b",
                                      "provider": "openai_compatible",
                                      "modelName": "kimi-k2.6",
                                      "baseUrl": "https://api.moonshot.cn/v1",
                                      "extraAuthJson": "{\\"pricingVerifiedAt\\":\\"2026-05-25\\",\\"taskType\\":\\"chat\\"}",
                                      "secretsRedacted": false,
                                      "timeoutSeconds": 60,
                                      "enabled": true,
                                      "agentEnabled": true,
                                      "isDefault": false,
                                      "capabilities": ["TEXT_GENERATION"]
                                    }
                                  ],
                                  "categories": [],
                                  "tools": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vendorAccounts").value(1))
                .andExpect(jsonPath("$.data.modelConfigs").value(1))
                .andExpect(jsonPath("$.data.warnings[?(@ =~ /.*MinerU.*/)]").isNotEmpty())
                .andExpect(jsonPath("$.data.warnings[?(@ =~ /.*non-auth metadata.*/)]").isNotEmpty());

        mockMvc.perform(get("/api/admin/v1/unified-api/overview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vendors[?(@.vendorCode=='mineru')]").isEmpty())
                .andExpect(jsonPath("$.data.vendors[?(@.vendorCode=='moonshot')].accounts[0].extraAuthJson")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.vendors[?(@.vendorCode=='moonshot')].accounts[0].extraAuthJsonMasked").value(""))
                .andExpect(jsonPath("$.data.vendors[?(@.vendorCode=='moonshot')].accounts.length()").value(1));

        mockMvc.perform(get("/api/admin/v1/agent/model-config/list")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.configCode=='test_kimi_import_cleanup')].extraAuthJsonMasked").value(""));
    }

    @Test
    void configBundleImportPrunesStaleModelsAndAccountsWithoutCredentials() throws Exception {
        String adminToken = loginAdmin();

        mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Stale No Key",
                                  "configCode": "stale_no_key_model",
                                  "provider": "openai_compatible",
                                  "modelName": "stale-model",
                                  "apiKey": "",
                                  "timeoutSeconds": 60,
                                  "enabled": false,
                                  "isDefault": false
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 1,
                                  "secretsRedacted": true,
                                  "settings": {},
                                  "vendorAccounts": [],
                                  "modelConfigs": [],
                                  "categories": [],
                                  "tools": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[?(@ =~ /.*stale_no_key_model.*/)]").isNotEmpty());

        mockMvc.perform(get("/api/admin/v1/agent/model-config/list")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.configCode=='stale_no_key_model')]").isEmpty());
    }

    @Test
    void configBundleImportCreatesFieldSchemaForLegacyToolWithoutSchema() throws Exception {
        String adminToken = loginAdmin();

        String categoryResponse = mockMvc.perform(post("/api/admin/v1/tool-categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryCode": "legacy_schema_test",
                                  "categoryName": "Legacy Schema Test",
                                  "sortOrder": 1,
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long categoryId = Long.parseLong(categoryResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        jdbcTemplate.update("""
                INSERT INTO ai_tools (
                  tool_code, tool_name, category_id, tool_type, input_modality, output_modality,
                  status, estimated_credit_cost, execution_handler, is_deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                "legacy_field_tool",
                "Legacy Field Tool",
                categoryId,
                "TEXT_GENERATION",
                "TEXT",
                "TEXT",
                "DRAFT",
                0,
                "TEXT_GENERATION");

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 1,
                                  "secretsRedacted": true,
                                  "settings": {},
                                  "categories": [
                                    {
                                      "categoryCode": "legacy_schema_test",
                                      "categoryName": "Legacy Schema Test",
                                      "sortOrder": 1,
                                      "status": "ACTIVE"
                                    }
                                  ],
                                  "tools": [
                                    {
                                      "toolCode": "legacy_field_tool",
                                      "toolName": "Legacy Field Tool",
                                      "categoryCode": "legacy_schema_test",
                                      "toolType": "TEXT_GENERATION",
                                      "inputModality": "TEXT",
                                      "outputModality": "TEXT",
                                      "status": "DRAFT",
                                      "estimatedCreditCost": 1,
                                      "executionHandler": "TEXT_GENERATION",
                                      "agentEnabled": false,
                                      "fields": [
                                        {
                                          "fieldKey": "prompt",
                                          "fieldName": "Prompt",
                                          "fieldType": "textarea",
                                          "required": true,
                                          "sortOrder": 1
                                        }
                                      ],
                                      "prompts": []
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools").value(1))
                .andExpect(jsonPath("$.data.fields").value(1))
                .andExpect(jsonPath("$.data.warnings").value(hasItem(
                        "Created missing field schema for tool legacy_field_tool before importing fields")));

        String toolsResponse = mockMvc.perform(get("/api/admin/v1/tools?page=1&pageSize=50&keyword=legacy_field_tool")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long toolId = Long.parseLong(toolsResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/fields", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.fieldKey=='prompt')].fieldName").value("Prompt"));
    }

    @Test
    void configBundleDraftWorkflowImportKeepsOnlinePublishedVersionExecutable() throws Exception {
        String adminToken = loginAdmin();
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_categories ORDER BY id LIMIT 1", Long.class);
        String categoryCode = jdbcTemplate.queryForObject(
                "SELECT category_code FROM tool_categories WHERE id = ?", String.class, categoryId);
        String toolResponse = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "config_bundle_online_workflow",
                                  "toolName": "Config Bundle Online Workflow",
                                  "categoryId": %d,
                                  "description": "config bundle workflow regression",
                                  "toolType": "TEXT_GENERATION",
                                  "inputModality": "TEXT",
                                  "outputModality": "TEXT",
                                  "estimatedCreditCost": 1,
                                  "executionHandler": "TEXT_GENERATION"
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long toolId = Long.parseLong(toolResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        String publishedNodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Published output"}}
                ]
                """;
        String edges = """
                [{"id":"e1","source":"start","target":"output"}]
                """;

        mockMvc.perform(put("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workflowName": "default",
                                  "nodesJson": %s,
                                  "edgesJson": %s,
                                  "expectedDraftRevision": 0
                                }
                                """.formatted(
                                objectMapper.writeValueAsString(publishedNodes),
                                objectMapper.writeValueAsString(edges))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowExecutionEnabled").value(true));
        Long publishedVersionId = jdbcTemplate.queryForObject(
                "SELECT published_version_id FROM tool_workflows WHERE tool_id = ? AND workflow_name = 'default'",
                Long.class,
                toolId);

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 1,
                                  "exportScope": "SELECTED_TOOLS",
                                  "secretsRedacted": true,
                                  "settings": {},
                                  "categories": [],
                                  "modelConfigs": [],
                                  "tools": [
                                    {
                                      "toolCode": "config_bundle_online_workflow",
                                      "toolName": "Config Bundle Online Workflow",
                                      "categoryCode": "%s",
                                      "description": "imported draft",
                                      "toolType": "TEXT_GENERATION",
                                      "inputModality": "TEXT",
                                      "outputModality": "TEXT",
                                      "status": "DRAFT",
                                      "estimatedCreditCost": 1,
                                      "executionHandler": "TEXT_GENERATION",
                                      "workflow": {
                                        "workflowName": "default",
                                        "nodes": [
                                          {"id":"start","data":{"nodeDefType":"start","title":"Imported draft"}},
                                          {"id":"output","data":{"nodeDefType":"video_output","title":"Draft output"}}
                                        ],
                                        "edges": [{"id":"e1","source":"start","target":"output"}],
                                        "status": "DRAFT",
                                        "version": 1
                                      }
                                    }
                                  ]
                                }
                                """.formatted(categoryCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools").value(1))
                .andExpect(jsonPath("$.data.workflows").value(1));

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.publishedVersionId").value(publishedVersionId))
                .andExpect(jsonPath("$.data.executionEnabled").value(true))
                .andExpect(jsonPath("$.data.hasUnpublishedChanges").value(true));
    }

    @Test
    void configBundleImportPreservesExistingToolCoverWhenImportedCoverIsEmpty() throws Exception {
        String adminToken = loginAdmin();

        String categoryResponse = mockMvc.perform(post("/api/admin/v1/tool-categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryCode": "cover_preserve_test",
                                  "categoryName": "Cover Preserve Test",
                                  "sortOrder": 1,
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long categoryId = Long.parseLong(categoryResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "cover_preserve_tool",
                                  "toolName": "Cover Preserve Tool",
                                  "categoryId": %d,
                                  "description": "Existing cover should survive empty imports",
                                  "coverUrl": "https://wlcloudai-assets-prod.oss-cn-guangzhou.aliyuncs.com/tool-covers/existing.png",
                                  "toolType": "IMAGE_GENERATION",
                                  "inputModality": "TEXT",
                                  "outputModality": "IMAGE",
                                  "status": "DRAFT",
                                  "estimatedCreditCost": 1,
                                  "executionHandler": "IMAGE_GENERATION",
                                  "agentEnabled": false
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/config-bundles/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "ai-tool-market-config-bundle",
                                  "version": 1,
                                  "secretsRedacted": true,
                                  "settings": {},
                                  "modelConfigs": [],
                                  "categories": [
                                    {
                                      "categoryCode": "cover_preserve_test",
                                      "categoryName": "Cover Preserve Test",
                                      "sortOrder": 1,
                                      "status": "ACTIVE"
                                    }
                                  ],
                                  "tools": [
                                    {
                                      "toolCode": "cover_preserve_tool",
                                      "toolName": "Cover Preserve Tool Imported",
                                      "categoryCode": "cover_preserve_test",
                                      "description": "Imported without a cover",
                                      "coverUrl": "",
                                      "toolType": "IMAGE_GENERATION",
                                      "inputModality": "TEXT",
                                      "outputModality": "IMAGE",
                                      "status": "DRAFT",
                                      "estimatedCreditCost": 2,
                                      "executionHandler": "IMAGE_GENERATION",
                                      "agentEnabled": false,
                                      "fields": [],
                                      "prompts": []
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings").value(hasItem(
                        "Preserved existing coverUrl for tool cover_preserve_tool because imported coverUrl was empty")));

        mockMvc.perform(get("/api/admin/v1/tools?page=1&pageSize=50&keyword=cover_preserve_tool")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].toolName").value("Cover Preserve Tool Imported"))
                .andExpect(jsonPath("$.data.list[0].coverUrl").value(
                        "https://wlcloudai-assets-prod.oss-cn-guangzhou.aliyuncs.com/tool-covers/existing.png"))
                .andExpect(jsonPath("$.data.list[0].estimatedCreditCost").value(2));
    }

    @Test
    void configBundleExportCanSelectToolsAndStripDisplayMediaUrls() throws Exception {
        String adminToken = loginAdmin();

        String categoryResponse = mockMvc.perform(post("/api/admin/v1/tool-categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryCode": "selective_export_test",
                                  "categoryName": "Selective Export Test",
                                  "sortOrder": 1,
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long categoryId = Long.parseLong(categoryResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        String doubaoModelResponse = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Doubao Video",
                                  "configCode": "doubao_video_model",
                                  "provider": "mock",
                                  "modelName": "doubao-video",
                                  "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",
                                  "timeoutSeconds": 60,
                                  "enabled": true,
                                  "isDefault": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long doubaoModelId = Long.parseLong(doubaoModelResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Other Image",
                                  "configCode": "other_image_model",
                                  "provider": "mock",
                                  "modelName": "other-image",
                                  "baseUrl": "https://example.test/api",
                                  "timeoutSeconds": 60,
                                  "enabled": true,
                                  "isDefault": false
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "doubao_video_tool",
                                  "toolName": "Doubao Video Tool",
                                  "categoryId": %d,
                                  "description": "Selected tool",
                                  "coverUrl": "http://localhost:8080/generated/effects/doubao.png",
                                  "toolType": "VIDEO_GENERATION",
                                  "inputModality": "TEXT",
                                  "outputModality": "VIDEO",
                                  "configNote": "<!-- ai-tool-ui:{\\"mediaDisplayMode\\":\\"comparison\\",\\"modelIconUrl\\":\\"http://localhost/icon.png\\",\\"comparisonOriginalUrl\\":\\"http://localhost/origin.png\\",\\"comparisonEffectUrl\\":\\"http://localhost/effect.png\\",\\"audioPreviewUrl\\":\\"http://localhost/audio.mp3\\",\\"heroTitle\\":\\"Doubao\\",\\"demoThumbnails\\":[\\"http://localhost/demo.png\\"]} -->",
                                  "estimatedCreditCost": 2,
                                  "modelConfigId": %d,
                                  "executionHandler": "VIDEO_GENERATION"
                                }
                                """.formatted(categoryId, doubaoModelId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "other_image_tool",
                                  "toolName": "Other Image Tool",
                                  "categoryId": %d,
                                  "description": "Unselected tool",
                                  "toolType": "IMAGE_GENERATION",
                                  "inputModality": "TEXT",
                                  "outputModality": "IMAGE",
                                  "estimatedCreditCost": 1,
                                  "executionHandler": "IMAGE_GENERATION"
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/v1/config-bundles/export")
                        .param("toolCodes", "doubao_video_tool")
                        .param("includeMediaAssets", "false")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exportScope").value("SELECTED_TOOLS"))
                .andExpect(jsonPath("$.data.settings").isEmpty())
                .andExpect(jsonPath("$.data.categories.length()").value(1))
                .andExpect(jsonPath("$.data.modelConfigs.length()").value(1))
                .andExpect(jsonPath("$.data.modelConfigs[0].configCode").value("doubao_video_model"))
                .andExpect(jsonPath("$.data.modelConfigs[0].baseUrl").value("https://ark.cn-beijing.volces.com/api/v3"))
                .andExpect(jsonPath("$.data.tools.length()").value(1))
                .andExpect(jsonPath("$.data.tools[0].toolCode").value("doubao_video_tool"))
                .andExpect(jsonPath("$.data.tools[0].coverUrl").value(""))
                .andExpect(jsonPath("$.data.tools[0].configNote")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http://localhost"))))
                .andExpect(jsonPath("$.data.tools[0].configNote")
                        .value(org.hamcrest.Matchers.containsString("\"modelIconUrl\":\"\"")));
    }

    @Test
    void selectedToolExportIncludesRoutingPoolExecutionClosure() throws Exception {
        String adminToken = loginAdmin();
        String poolKey = "selected export closure pool";
        jdbcTemplate.update("""
                INSERT INTO model_account_routing_pools(vendor_code, pool_name, pool_key)
                VALUES ('mock', 'Selected Export Closure Pool', ?)
                """, poolKey);
        Long poolId = jdbcTemplate.queryForObject(
                "SELECT id FROM model_account_routing_pools WHERE vendor_code = 'mock' AND pool_key = ?",
                Long.class,
                poolKey
        );
        jdbcTemplate.update("""
                INSERT INTO model_vendor_accounts(
                    vendor_code, account_name, base_url, api_key,
                    balance_query_mode, balance_status, health_status,
                    load_balance_enabled, load_balance_weight, routing_pool_id,
                    enabled, is_deleted
                ) VALUES ('mock', 'selected-export-source', 'https://selected-export-source.example/v1', 'source-key',
                          'MANUAL', 'UNKNOWN', 'UNKNOWN', 1, 100, ?, 1, 0)
                """, poolId);
        jdbcTemplate.update("""
                INSERT INTO model_vendor_accounts(
                    vendor_code, account_name, base_url, api_key,
                    balance_query_mode, balance_status, health_status,
                    load_balance_enabled, load_balance_weight, routing_pool_id,
                    enabled, is_deleted
                ) VALUES ('mock', 'selected-export-candidate', 'https://selected-export-candidate.example/v1', 'candidate-key',
                          'MANUAL', 'UNKNOWN', 'UNKNOWN', 1, 80, ?, 1, 0)
                """, poolId);
        Long sourceAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM model_vendor_accounts WHERE vendor_code = 'mock' AND account_name = 'selected-export-source'",
                Long.class
        );
        Long candidateAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM model_vendor_accounts WHERE vendor_code = 'mock' AND account_name = 'selected-export-candidate'",
                Long.class
        );
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                    vendor_account_id, routing_pool_id, display_name, config_code, provider, model_name,
                    billing_unit, unit_price, capabilities, enabled, agent_enabled, is_default, is_deleted
                ) VALUES (?, ?, 'Selected export source', 'selected_export_source_model', 'mock', 'shared-upstream-model',
                          'PER_CALL', 1.25, '["TEXT_GENERATION"]', 1, 1, 0, 0)
                """, sourceAccountId, poolId);
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                    vendor_account_id, display_name, config_code, provider, model_name,
                    billing_unit, unit_price, capabilities, enabled, agent_enabled, is_default, is_deleted
                ) VALUES (?, 'Selected export candidate', 'selected_export_candidate_model', 'mock', 'shared-upstream-model',
                          'PER_CALL', 1.25, '["TEXT_GENERATION"]', 1, 1, 0, 0)
                """, candidateAccountId);
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                    vendor_account_id, display_name, config_code, provider, model_name,
                    billing_unit, unit_price, capabilities, enabled, agent_enabled, is_default, is_deleted
                ) VALUES (?, 'Selected export price mismatch', 'selected_export_price_mismatch_model', 'mock', 'shared-upstream-model',
                          'PER_CALL', 2.50, '["TEXT_GENERATION"]', 1, 1, 0, 0)
                """, candidateAccountId);
        Long sourceModelId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_model_configs WHERE config_code = 'selected_export_source_model'",
                Long.class
        );

        String categoryResponse = mockMvc.perform(post("/api/admin/v1/tool-categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryCode": "selected_export_closure_test",
                                  "categoryName": "Selected Export Closure Test",
                                  "sortOrder": 1,
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long categoryId = Long.parseLong(categoryResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "selected_export_closure_tool",
                                  "toolName": "Selected Export Closure Tool",
                                  "categoryId": %d,
                                  "description": "Selected pool tool",
                                  "toolType": "TEXT_GENERATION",
                                  "inputModality": "TEXT",
                                  "outputModality": "TEXT",
                                  "estimatedCreditCost": 1,
                                  "modelConfigId": %d,
                                  "executionHandler": "TEXT_GENERATION"
                                }
                                """.formatted(categoryId, sourceModelId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/v1/config-bundles/export")
                        .param("toolCodes", "selected_export_closure_tool")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.vendorAccounts.length()").value(2))
                .andExpect(jsonPath("$.data.vendorAccounts[*].accountName").value(hasItem("selected-export-source")))
                .andExpect(jsonPath("$.data.vendorAccounts[*].accountName").value(hasItem("selected-export-candidate")))
                .andExpect(jsonPath("$.data.modelConfigs.length()").value(2))
                .andExpect(jsonPath("$.data.modelConfigs[*].configCode").value(hasItem("selected_export_source_model")))
                .andExpect(jsonPath("$.data.modelConfigs[*].configCode").value(hasItem("selected_export_candidate_model")))
                .andExpect(jsonPath("$.data.modelConfigs[*].configCode").value(
                        org.hamcrest.Matchers.not(hasItem("selected_export_price_mismatch_model"))))
                .andExpect(jsonPath("$.data.modelConfigs[?(@.configCode=='selected_export_source_model')].routingPoolName")
                        .value("Selected Export Closure Pool"));
    }

    private String loginAdmin() throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "admin",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
    }
}
