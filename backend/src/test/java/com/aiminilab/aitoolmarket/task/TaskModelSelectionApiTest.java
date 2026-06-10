package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:task_model_selection_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class TaskModelSelectionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createTaskStoresRequestedModelConfigAndWorkerContextUsesIt() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long defaultImageModelId = createImageModelConfig(adminToken, "tool_default_image_model", "tool-default-image-model");
        Long selectedImageModelId = createImageModelConfig(adminToken, "request_selected_image_model", "request-selected-image-model");
        Long toolId = createImageTool(adminToken, "task_requested_image_model_tool", defaultImageModelId);
        publishTool(adminToken, toolId);

        String userToken = login("/api/v1/auth/login", "user1");
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "task_requested_image_model_tool",
                                  "modelConfigId": %d,
                                  "params": {
                                    "prompt": "Generate a product image"
                                  },
                                  "clientRequestId": "task-requested-image-model"
                                }
                                """.formatted(selectedImageModelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskNo", not(blankOrNullString())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long taskId = Long.parseLong(response.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelConfigId").value(selectedImageModelId.intValue()))
                .andExpect(jsonPath("$.data.modelConfigName").value("request-selected-image-model"))
                .andExpect(jsonPath("$.data.modelName").value("request-selected-image-model"));

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[?(@.taskId==%d)].modelConfigId".formatted(taskId)).value(selectedImageModelId.intValue()))
                .andExpect(jsonPath("$.data.list[?(@.taskId==%d)].modelConfigName".formatted(taskId)).value("request-selected-image-model"));

        mockMvc.perform(signed(get("/api/internal/v1/tasks/{taskId}/execution-context", taskId), "GET",
                        "/api/internal/v1/tasks/%d/execution-context".formatted(taskId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelConfig.id").value(selectedImageModelId.intValue()))
                .andExpect(jsonPath("$.data.modelConfig.modelName").value("request-selected-image-model"));
    }

    @Test
    void createTaskRejectsRequestedModelConfigWithWrongCapability() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long imageModelId = createImageModelConfig(adminToken, "wrong_capability_default_image", "wrong-capability-default-image");
        Long textModelId = createTextModelConfig(adminToken, "wrong_capability_text_model", "wrong-capability-text-model");
        Long toolId = createImageTool(adminToken, "task_wrong_capability_tool", imageModelId);
        publishTool(adminToken, toolId);

        String userToken = login("/api/v1/auth/login", "user1");
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "task_wrong_capability_tool",
                                  "modelConfigId": %d,
                                  "params": {
                                    "prompt": "Generate a product image"
                                  },
                                  "clientRequestId": "task-wrong-capability"
                                }
                                """.formatted(textModelId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));
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

    private Long createImageModelConfig(String adminToken, String configCode, String modelName) throws Exception {
        return createModelConfig(adminToken, configCode, "siliconflow_images", modelName,
                "https://api.siliconflow.cn", "PER_CALL", "[\"IMAGE_GENERATION\"]");
    }

    private Long createTextModelConfig(String adminToken, String configCode, String modelName) throws Exception {
        return createModelConfig(adminToken, configCode, "openai_compatible", modelName,
                "https://api.openai.com/v1", "TOKEN_PER_M", "[\"TEXT_GENERATION\"]");
    }

    private Long createModelConfig(String adminToken, String configCode, String provider, String modelName,
                                   String baseUrl, String billingUnit, String capabilitiesJson) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "%s",
                                  "configCode": "%s",
                                  "provider": "%s",
                                  "modelName": "%s",
                                  "baseUrl": "%s",
                                  "apiKey": "task-model-secret",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "%s",
                                  "unitPrice": 0.03,
                                  "capabilities": %s,
                                  "enabled": true,
                                  "agentEnabled": true,
                                  "isDefault": false
                                }
                                """.formatted(modelName, configCode, provider, modelName, baseUrl, billingUnit, capabilitiesJson)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createImageTool(String adminToken, String toolCode, Long modelConfigId) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "toolName": "%s",
                                  "categoryId": 1,
                                  "description": "Task model selection test tool",
                                  "coverUrl": "",
                                  "toolType": "IMAGE_GENERATION",
                                  "inputModality": "TEXT",
                                  "outputModality": "IMAGE",
                                  "executionHandler": "IMAGE_GENERATION",
                                  "modelConfigId": %d,
                                  "estimatedCreditCost": 3
                                }
                                """.formatted(toolCode, toolCode, modelConfigId)))
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
}
