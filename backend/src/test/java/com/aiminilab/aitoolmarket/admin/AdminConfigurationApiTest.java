package com.aiminilab.aitoolmarket.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
                .andExpect(jsonPath("$.data.warnings[0]").value("Reused existing model config siliconflow_image_turbo for imported model config model_99"));

        mockMvc.perform(get("/api/admin/v1/agent/model-config/list")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.provider=='siliconflow_images' && @.modelName=='Tongyi-MAI/Z-Image-Turbo')]").isArray())
                .andExpect(jsonPath("$.data[?(@.configCode=='model_99')]").isEmpty());

        mockMvc.perform(get("/api/admin/v1/tools?page=1&pageSize=50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[?(@.toolCode=='dup_tool')].modelConfigId").value(existingModelId.intValue()))
                .andExpect(jsonPath("$.data.list[?(@.toolCode=='dup_tool')].executionHandler").value("IMAGE_GENERATION"));

        mockMvc.perform(get("/api/admin/v1/agent/tools")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.toolCode=='dup_tool')].agentEnabled").value(false));

        mockMvc.perform(get("/api/admin/v1/config-bundles/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools[?(@.toolCode=='dup_tool')].executionHandler").value("IMAGE_GENERATION"))
                .andExpect(jsonPath("$.data.tools[?(@.toolCode=='dup_tool')].agentEnabled").value(false));
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
