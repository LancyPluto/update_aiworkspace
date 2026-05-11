package com.aiminilab.aitoolmarket.tool;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_tool_field_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AdminToolFieldApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminFieldSchemasContractPathWorks() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "schema_path_tool");

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/field-schemas", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].schemaVersion").exists())
                .andExpect(jsonPath("$.data[0].items[0].fieldKey").value("productName"));

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/field-schemas", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schemaVersion": "v2",
                                  "items": [
                                    {
                                      "fieldKey": "topic",
                                      "fieldName": "主题",
                                      "fieldType": "text",
                                      "placeholder": "输入主题",
                                      "required": true,
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaVersion").value("v2"))
                .andExpect(jsonPath("$.data.items[0].fieldKey").value("topic"));

        String schemaId = mockMvc.perform(get("/api/admin/v1/tools/{toolId}/field-schemas", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1");

        mockMvc.perform(post("/api/admin/v1/field-schemas/{schemaId}/publish", Long.parseLong(schemaId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void adminCanReplaceFieldsAndUserAndWorkerReadSameFields() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "field_config_tool");

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/fields", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fieldKey").value("productName"));

        mockMvc.perform(put("/api/admin/v1/tools/{toolId}/fields", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fields": [
                                    {
                                      "fieldKey": "brandName",
                                      "fieldName": "Brand Name",
                                      "fieldType": "text",
                                      "placeholder": "Input brand name",
                                      "required": true,
                                      "sortOrder": 1
                                    },
                                    {
                                      "fieldKey": "tone",
                                      "fieldName": "Tone",
                                      "fieldType": "select",
                                      "placeholder": "Choose tone",
                                      "options": [
                                        {"label": "Friendly", "value": "friendly"},
                                        {"label": "Professional", "value": "professional"}
                                      ],
                                      "required": false,
                                      "sortOrder": 2
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fieldKey").value("brandName"))
                .andExpect(jsonPath("$.data[1].fieldKey").value("tone"))
                .andExpect(jsonPath("$.data[1].options[0].value").value("friendly"))
                .andExpect(jsonPath("$.data[1].required").value(false));

        publishTool(adminToken, toolId);

        mockMvc.perform(get("/api/v1/tools/field_config_tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("brandName"))
                .andExpect(jsonPath("$.data.fields[1].fieldKey").value("tone"))
                .andExpect(jsonPath("$.data.fields[1].options[1].label").value("Professional"));

        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken);

        mockMvc.perform(get("/api/internal/v1/tasks/{taskId}/execution-context", taskId)
                        .header("X-Internal-Token", "local-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("brandName"))
                .andExpect(jsonPath("$.data.fields[1].fieldKey").value("tone"));
    }

    @Test
    void userTokenCannotReplaceAdminToolFields() throws Exception {
        String userToken = login("/api/v1/auth/login", "user1");

        mockMvc.perform(put("/api/admin/v1/tools/{toolId}/fields", 1)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fields": []
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
    }

    private String login(String path, String account) throws Exception {
        var result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "123456"
                                }
                                """.formatted(account)))
                .andExpect(status().isOk())
                .andReturn();
        if (path.contains("/admin/")) {
            return AuthTestTokens.adminJwtFrom(result);
        }
        return AuthTestTokens.userJwtFrom(result);
    }

    private Long createTool(String adminToken, String toolCode) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "toolName": "%s",
                                  "categoryId": 1,
                                  "description": "Field config test tool",
                                  "coverUrl": "",
                                  "estimatedCreditCost": 5
                                }
                                """.formatted(toolCode, toolCode)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private void publishTool(String adminToken, Long toolId) throws Exception {
        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private Long createTask(String userToken) throws Exception {
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "field_config_tool",
                                  "params": {
                                    "brandName": "MiniLab",
                                    "tone": "friendly"
                                  },
                                  "clientRequestId": "field-config-request"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
