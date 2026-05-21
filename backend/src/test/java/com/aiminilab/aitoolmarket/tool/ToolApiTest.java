package com.aiminilab.aitoolmarket.tool;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tool_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.generated-media-dir=target/test-generated-media"
})
class ToolApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanCreateEditPublishAndOfflineToolForUserMarketplace() throws Exception {
        String adminToken = loginAdmin();

        mockMvc.perform(get("/api/v1/tool-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].categoryCode").value("copywriting"));

        String createResponse = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "xiaohongshu_copywriting",
                                  "toolName": "小红书文案生成",
                                  "categoryId": 1,
                                  "description": "根据产品信息生成小红书文案",
                                  "coverUrl": "https://cdn.example.com/tools/xiaohongshu-cover.webp",
                                  "estimatedCreditCost": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.coverUrl").value("https://cdn.example.com/tools/xiaohongshu-cover.webp"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String toolId = createResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1");

        mockMvc.perform(put("/api/admin/v1/tools/" + toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolName": "小红书文案助手",
                                  "categoryId": 1,
                                  "description": "更新后的说明",
                                  "coverUrl": "https://cdn.example.com/tools/xiaohongshu-preview.mp4",
                                  "estimatedCreditCost": 12
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolName").value("小红书文案助手"))
                .andExpect(jsonPath("$.data.coverUrl").value("https://cdn.example.com/tools/xiaohongshu-preview.mp4"))
                .andExpect(jsonPath("$.data.estimatedCreditCost").value(12));

        mockMvc.perform(post("/api/admin/v1/tools/" + toolId + "/publish")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"));

        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].toolCode").value("xiaohongshu_copywriting"))
                .andExpect(jsonPath("$.data.list[0].coverUrl").value("https://cdn.example.com/tools/xiaohongshu-preview.mp4"))
                .andExpect(jsonPath("$.data.list[0].toolName").value("小红书文案助手"));

        mockMvc.perform(get("/api/v1/tools/xiaohongshu_copywriting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("xiaohongshu_copywriting"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("productName"));

        mockMvc.perform(post("/api/admin/v1/tools/" + toolId + "/offline")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFFLINE"));

        mockMvc.perform(get("/api/v1/tools")
                        .param("keyword", "xiaohongshu_copywriting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void userCanFilterAndSearchOnlineToolsAndAdminCanViewToolDetail() throws Exception {
        String adminToken = loginAdmin();
        Long writingToolId = createTool(adminToken, "search_copywriting", "Search Copy Tool", 1, "Write product copy", 3);
        Long hiddenToolId = createTool(adminToken, "hidden_draft_tool", "Hidden Draft Tool", 1, "Draft only", 3);
        publishTool(adminToken, writingToolId);

        mockMvc.perform(get("/api/v1/tools")
                        .param("keyword", "copy")
                        .param("categoryId", "1")
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNo").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.list[0].toolCode").value("search_copywriting"));

        mockMvc.perform(get("/api/v1/tools/search")
                        .param("keyword", "product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].toolCode").value("search_copywriting"));

        mockMvc.perform(get("/api/admin/v1/tools")
                        .param("status", "DRAFT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(hiddenToolId.intValue()));

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}", writingToolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("search_copywriting"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("productName"));
    }

    @Test
    void adminCanCreateToolWithoutCodeAndPublishItForUserMarketplace() throws Exception {
        String adminToken = loginAdmin();

        String createResponse = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolName": "No Code Tool",
                                  "categoryId": 1,
                                  "description": "Created from admin form",
                                  "coverUrl": "",
                                  "estimatedCreditCost": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.toolCode").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long toolId = Long.parseLong(createResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        String toolCode = createResponse.replaceAll("(?s).*\\\"toolCode\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        publishTool(adminToken, toolId);

        mockMvc.perform(get("/api/v1/tools")
                        .param("keyword", "No Code Tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].toolCode").value(toolCode));
    }

    @Test
    void adminCanDeleteToolFromManagementList() throws Exception {
        String adminToken = loginAdmin();
        Long toolId = createTool(adminToken, "delete_me_tool", "Delete Me Tool", 1, "temporary", 1);

        mockMvc.perform(delete("/api/admin/v1/tools/{toolId}", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(get("/api/admin/v1/tools")
                        .param("keyword", "delete_me_tool")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(delete("/api/admin/v1/tools/{toolId}", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void adminCanUploadToolCoverPreviewAsset() throws Exception {
        String adminToken = loginAdmin();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "preview.mp4",
                "video/mp4",
                "fake-video-content".getBytes()
        );

        mockMvc.perform(multipart("/api/admin/v1/tools/cover-upload")
                        .file(file)
                        .param("toolName", "视频换脸器")
                        .param("toolCode", "video_face_swap")
                        .param("modelName", "seedance-2.0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("/generated/tool-covers/")))
                .andExpect(jsonPath("$.data.filename").value(org.hamcrest.Matchers.containsString("视频换脸器")))
                .andExpect(jsonPath("$.data.filename").value(org.hamcrest.Matchers.containsString("seedance-2.0")))
                .andExpect(jsonPath("$.data.contentType").value("video/mp4"));
    }

    @Test
    void adminCanPersistPromptVersionsAndPublishActiveVersion() throws Exception {
        String adminToken = loginAdmin();
        Long toolId = createTool(adminToken, "prompt_tool", "Prompt Tool", 1, "Prompt persistence", 2);

        String promptResponse = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/prompts", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "promptCode": "default",
                                  "promptName": "Default Prompt"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.toolId").value(toolId.intValue()))
                .andExpect(jsonPath("$.data.promptCode").value("default"))
                .andExpect(jsonPath("$.data.activeVersionId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long promptId = Long.parseLong(promptResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        String versionResponse = mockMvc.perform(post("/api/admin/v1/prompts/{promptId}/versions", promptId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "versionNo": "v1",
                                  "systemPrompt": "You are a concise assistant.",
                                  "userPromptTemplate": "Write about {{topic}} for {{audience}}.",
                                  "outputFormat": "MARKDOWN"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.promptId").value(promptId.intValue()))
                .andExpect(jsonPath("$.data.versionNo").value("v1"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long versionId = Long.parseLong(versionResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/admin/v1/prompts/{promptId}/versions", promptId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(versionId.intValue()))
                .andExpect(jsonPath("$.data[0].userPromptTemplate").value("Write about {{topic}} for {{audience}}."));

        mockMvc.perform(post("/api/admin/v1/prompt-versions/{versionId}/test-generate", versionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "params": {
                                    "topic": "AI tools",
                                    "audience": "operators"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output").value("Write about AI tools for operators."));

        mockMvc.perform(post("/api/admin/v1/prompt-versions/{versionId}/publish", versionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.publishedAt").exists());

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/prompts", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(promptId.intValue()))
                .andExpect(jsonPath("$.data[0].activeVersionId").value(versionId.intValue()));
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

    private Long createTool(String adminToken, String toolCode, String toolName, long categoryId,
                            String description, int estimatedCreditCost) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "toolName": "%s",
                                  "categoryId": %d,
                                  "description": "%s",
                                  "coverUrl": "",
                                  "estimatedCreditCost": %d
                                }
                                """.formatted(toolCode, toolName, categoryId, description, estimatedCreditCost)))
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
