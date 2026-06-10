package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:model_options_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class ModelOptionsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicModelOptionsGroupsEnabledAgentModelsByVendorWithoutSecrets() throws Exception {
        String adminToken = loginAdmin();
        createModelConfig(adminToken, "public_image_enabled", "Public Image Enabled", "siliconflow_images",
                "Tongyi-MAI/Z-Image-Turbo", true, true,
                """
                {
                  "imageSizes": [
                    {"label": "方图 1024", "value": "1024x1024"},
                    {"label": "?? 1536", "value": "1536x1024"}
                  ],
                  "defaultImageSize": "1536x1024",
                  "counts": [1, 2],
                  "defaultCount": 2,
                  "qualities": [
                    {"label": "Low", "value": "low"},
                    {"label": "High", "value": "high"}
                  ],
                  "defaultQuality": "high"
                }
                """);
        createModelConfig(adminToken, "public_image_disabled", "Public Image Disabled", "siliconflow_images",
                "disabled-image-model", false, true, "");
        createModelConfig(adminToken, "public_image_agent_disabled", "Public Image Agent Disabled", "siliconflow_images",
                "agent-disabled-image-model", true, false, "");

        String response = mockMvc.perform(get("/api/v1/model-options")
                        .param("mode", "image"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].vendorCode").value("siliconflow"))
                .andExpect(jsonPath("$.data[0].models[0].configCode").value("public_image_enabled"))
                .andExpect(jsonPath("$.data[0].models[0].capabilities[0]").value("IMAGE_GENERATION"))
                .andExpect(jsonPath("$.data[0].models[0].imageParameters.sizes[0].label").value("方图 1024"))
                .andExpect(jsonPath("$.data[0].models[0].imageParameters.sizes[0].value").value("1024x1024"))
                .andExpect(jsonPath("$.data[0].models[0].imageParameters.sizes[1].label").value("1536x1024"))
                .andExpect(jsonPath("$.data[0].models[0].imageParameters.defaultSize").value("1536x1024"))
                .andExpect(jsonPath("$.data[0].models[0].imageParameters.counts[1]").value(2))
                .andExpect(jsonPath("$.data[0].models[0].imageParameters.defaultCount").value(2))
                .andExpect(jsonPath("$.data[0].models[0].imageParameters.qualities[1].value").value("high"))
                .andExpect(jsonPath("$.data[0].models[0].imageParameters.defaultQuality").value("high"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("public_image_enabled");
        assertThat(response).doesNotContain("public_image_disabled");
        assertThat(response).doesNotContain("public_image_agent_disabled");
        assertThat(response).doesNotContain("apiKey");
        assertThat(response).doesNotContain("extraAuth");
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

    private void createModelConfig(String adminToken, String configCode, String displayName, String provider,
                                   String modelName, boolean enabled, boolean agentEnabled, String extraAuthJson) throws Exception {
        mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "%s",
                                  "configCode": "%s",
                                  "provider": "%s",
                                  "modelName": "%s",
                                  "baseUrl": "https://api.siliconflow.cn",
                                  "apiKey": "public-option-secret",
                                  "timeoutSeconds": 60,
                                  "extraAuthJson": %s,
                                  "billingUnit": "PER_CALL",
                                  "unitPrice": 0.03,
                                  "capabilities": ["IMAGE_GENERATION"],
                                  "enabled": %s,
                                  "agentEnabled": %s,
                                  "isDefault": false
                                }
                                """.formatted(displayName, configCode, provider, modelName,
                                extraAuthJson == null || extraAuthJson.isBlank()
                                        ? "\"\""
                                        : "\"" + extraAuthJson.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"",
                                enabled, agentEnabled)))
                .andExpect(status().isOk());
    }
}
