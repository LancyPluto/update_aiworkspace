package com.aiminilab.aitoolmarket.tool;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作流画布管理链路：保存（保持状态）→ 校验 → 发布 → 再保存不掉发布状态 → 下线。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_workflow_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AdminWorkflowApiTest {

    private static final String VALID_NODES = """
            [
              {"id":"start","data":{"nodeDefType":"start","title":"开始"}},
              {"id":"output","data":{"nodeDefType":"video_output","title":"成片输出"}}
            ]
            """;
    private static final String VALID_EDGES = """
            [{"id":"e1","source":"start","target":"output"}]
            """;
    private static final String INVALID_NODES = """
            [{"id":"start","data":{"nodeDefType":"start","title":"开始"}}]
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publishRequiresValidDagAndSaveKeepsPublishedStatus() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_publish_tool");

        // 1. 保存非法 DAG（缺 video_output）：保存成功但校验不通过、发布被拒绝
        saveWorkflow(adminToken, toolId, INVALID_NODES, "[]");

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/validate", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.errors[0]").value(containsString("video_output")));

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));

        // 2. 保存合法 DAG 并发布
        saveWorkflow(adminToken, toolId, VALID_NODES, VALID_EDGES);

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/validate", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // 3. 再次保存内容（不带 status）：版本 +1 且发布状态保持，不会被打回 DRAFT
        saveWorkflow(adminToken, toolId, VALID_NODES, VALID_EDGES);

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // 4. 下线后回到 DRAFT
        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/unpublish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // 5. 历史版本可列出
        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow/versions", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").exists());
    }

    private void saveWorkflow(String adminToken, Long toolId, String nodesJson, String edgesJson) throws Exception {
        String body = """
                {
                  "workflowName": "default",
                  "nodesJson": %s,
                  "edgesJson": %s
                }
                """.formatted(jsonString(nodesJson), jsonString(edgesJson));
        mockMvc.perform(put("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    private static String jsonString(String raw) {
        return "\"" + raw.trim().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "") + "\"";
    }

    private Long createTool(String adminToken, String toolCode) throws Exception {
        String body = """
                {
                  "toolCode": "%s",
                  "toolName": "Workflow Tool",
                  "categoryId": 1,
                  "description": "workflow publish test",
                  "estimatedCreditCost": 1
                }
                """.formatted(toolCode);
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private String login() throws Exception {
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
}
