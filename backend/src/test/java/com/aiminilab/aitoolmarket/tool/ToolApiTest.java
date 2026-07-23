package com.aiminilab.aitoolmarket.tool;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.aiminilab.aitoolmarket.common.cache.CacheNamespaces;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

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
                .andExpect(jsonPath("$.data[0].categoryCode").value("text-to-image"));

        String createResponse = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "xiaohongshu_copywriting",
                                  "toolName": "小红书文案生成",
                                  "categoryId": 4,
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

        mockMvc.perform(put("/api/admin/v1/tools/" + toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolName": "XHS Copywriting Assistant",
                                  "categoryId": 1,
                                  "description": "update text without clearing preview",
                                  "coverUrl": "",
                                  "estimatedCreditCost": 12
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverUrl").value("https://cdn.example.com/tools/xiaohongshu-preview.mp4"));

        mockMvc.perform(post("/api/admin/v1/tools/" + toolId + "/publish")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"));

        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].toolCode").value("xiaohongshu_copywriting"))
                .andExpect(jsonPath("$.data.list[0].coverUrl").value("https://cdn.example.com/tools/xiaohongshu-preview.mp4"))
                .andExpect(jsonPath("$.data.list[0].toolName").value("XHS Copywriting Assistant"));

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
                .andExpect(jsonPath("$.data.list[*].id").value(hasItem(hiddenToolId.intValue())));

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}", writingToolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("search_copywriting"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("productName"));
    }

    @Test
    void publicToolListRejectsUnsupportedView() throws Exception {
        mockMvc.perform(get("/api/v1/tools")
                        .param("view", "full"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value("view must be one of: summary, compact"));
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

    @Test
    void userToolListShowsPerCallEstimatedCreditsFromModelConfig() throws Exception {
        String adminToken = loginAdmin();

        String modelResponse = mockMvc.perform(put("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "siliconflow_images",
                                  "modelName": "Tongyi-MAI/Z-Image-Turbo",
                                  "baseUrl": "https://api.siliconflow.cn",
                                  "apiKey": "fake-key",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "PER_CALL",
                                  "unitPrice": 0.03,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long modelConfigId = Long.parseLong(modelResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        String createResponse = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "per_call_credit_display_tool",
                                  "toolName": "Per Call Credit Display",
                                  "categoryId": 1,
                                  "description": "credit estimate test",
                                  "coverUrl": "",
                                  "estimatedCreditCost": 99,
                                  "toolType": "IMAGE_GENERATION",
                                  "modelConfigId": %d
                                }
                                """.formatted(modelConfigId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long toolId = Long.parseLong(createResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        publishTool(adminToken, toolId);

        mockMvc.perform(get("/api/v1/tools")
                        .queryParam("pageNo", "1")
                        .queryParam("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[?(@.toolCode=='per_call_credit_display_tool')].estimatedCreditCost")
                        .value(hasItem(4)));

        mockMvc.perform(get("/api/admin/v1/tools")
                        .queryParam("pageNo", "1")
                        .queryParam("pageSize", "100")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[?(@.toolCode=='per_call_credit_display_tool')].estimatedCreditCost")
                        .value(hasItem(4)));
    }

    @Test
    void unboundToolUsesStaticEstimatedCreditsAcrossPublicViewsAndDetail() throws Exception {
        String adminToken = loginAdmin();

        mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Unbound Fallback Guard Model",
                                  "configCode": "unbound_fallback_guard_model",
                                  "provider": "siliconflow_images",
                                  "modelName": "Tongyi-MAI/Z-Image-Turbo",
                                  "baseUrl": "https://api.siliconflow.cn",
                                  "apiKey": "fake-key",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "PER_CALL",
                                  "unitPrice": 0.03,
                                  "capabilities": ["IMAGE_GENERATION"],
                                  "enabled": true,
                                  "agentEnabled": false,
                                  "isDefault": false
                                }
                                """))
                .andExpect(status().isOk());

        String createResponse = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "unbound_static_credit_tool",
                                  "toolName": "Unbound Static Credit Tool",
                                  "categoryId": 2,
                                  "description": "static fallback credit regression guard",
                                  "coverUrl": "",
                                  "toolType": "IMAGE_GENERATION",
                                  "inputModality": "TEXT",
                                  "outputModality": "IMAGE",
                                  "executionHandler": "IMAGE_GENERATION",
                                  "estimatedCreditCost": 37
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelConfigId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long toolId = Long.parseLong(createResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        publishTool(adminToken, toolId);

        mockMvc.perform(get("/api/v1/tools")
                        .param("keyword", "unbound_static_credit_tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].estimatedCreditCost").value(37));

        mockMvc.perform(get("/api/v1/tools")
                        .param("keyword", "unbound_static_credit_tool")
                        .param("view", "compact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].estimatedCreditCost").value(37));

        mockMvc.perform(get("/api/v1/tools/unbound_static_credit_tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimatedCreditCost").value(37));
    }

    @Test
    void publicToolResponsesDoNotExposeInternalConfigNotesOrEngineSecrets() throws Exception {
        String adminToken = loginAdmin();
        Long modelConfigId = createPublicContractModelConfig(adminToken);

        String createResponse = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "public_secret_guard_tool",
                                  "toolName": "Public Secret Guard Tool",
                                  "categoryId": 2,
                                  "description": "safe public description",
                                  "coverUrl": "https://cdn.example.com/public-tool.webp",
                                  "toolType": "TEXT_GENERATION",
                                  "inputModality": "TEXT",
                                  "outputModality": "TEXT",
                                  "estimatedCreditCost": 5,
                                  "modelConfigId": %d,
                                  "executionHandler": "TEXT_GENERATION",
                                  "configNote": "operator only note\\n\\n<!-- ai-tool-ui:{\\"primaryColor\\":\\"#123456\\",\\"welcomeMessage\\":\\"\\",\\"heroTitle\\":\\"Safe Hero\\",\\"heroSubtitle\\":\\"Safe subtitle\\",\\"demoThumbnails\\":[\\"https://cdn.example.com/one.webp\\",\\"https://cdn.example.com/two.webp\\"]} -->\\n\\n<!-- ppt-workflow:{\\"integrationMode\\":\\"PPT_WORKSPACE\\",\\"customUiRoute\\":\\"/tools/public_secret_guard_tool/workspace\\",\\"creationTypes\\":[\\"idea\\"],\\"steps\\":[{\\"code\\":\\"CREATE\\",\\"name\\":\\"Create\\",\\"credits\\":5,\\"enabled\\":true}],\\"engineSecrets\\":{\\"mineru_token\\":\\"secret-token\\"},\\"engineSecretSources\\":{\\"mineru_token\\":\\"manual\\"}} -->"
                                }
                                """.formatted(modelConfigId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long toolId = Long.parseLong(createResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(put("/api/admin/v1/tools/{toolId}/fields", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fields": [{
                                    "fieldKey": "productName",
                                    "fieldName": "Product Name",
                                    "fieldType": "TEXT",
                                    "options": {
                                      "core": true,
                                      "maxCount": 4,
                                      "baseUrl": "https://private-field-upstream.example.com",
                                      "engineSecrets": {"token": "field-options-secret"}
                                    },
                                    "required": true,
                                    "executionRequired": true,
                                    "userRequired": true,
                                    "agentFillStrategy": "ask_user",
                                    "riskLevel": "LOW",
                                    "sortOrder": 1
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fieldType").value("TEXT"))
                .andExpect(jsonPath("$.data[0].options.engineSecrets.token").value("field-options-secret"));
        publishTool(adminToken, toolId);

        ResultActions listResponse = mockMvc.perform(get("/api/v1/tools")
                        .param("keyword", "public_secret_guard_tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].toolCode").value("public_secret_guard_tool"))
                .andExpect(jsonPath("$.data.list[0].modelDisplayName").value("Public Contract Model"))
                .andExpect(jsonPath("$.data.list[0].cardMedia", aMapWithSize(3)))
                .andExpect(jsonPath("$.data.list[0].cardMedia.mediaDisplayMode").value("icon"))
                .andExpect(jsonPath("$.data.list[0].cardMedia.heroSubtitle").value("Safe subtitle"))
                .andExpect(jsonPath("$.data.list[0].cardMedia.demoThumbnails", hasItem("https://cdn.example.com/one.webp")))
                .andExpect(jsonPath("$.data.list[0].cardMedia.demoThumbnails[1]").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].cardMedia.heroTitle").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].cardMedia.primaryColor").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].frontendStyle").doesNotExist());
        assertPublicToolContract(listResponse, "$.data.list[0]", 14);

        ResultActions compactResponse = mockMvc.perform(get("/api/v1/tools")
                        .param("keyword", "public_secret_guard_tool")
                        .param("view", "compact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].toolCode").value("public_secret_guard_tool"))
                .andExpect(jsonPath("$.data.list[0].estimatedCreditCost").value(5))
                .andExpect(jsonPath("$.data.list[0].modelDisplayName").value("Public Contract Model"))
                .andExpect(jsonPath("$.data.list[0].cardMedia").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].frontendStyle").doesNotExist());
        assertPublicToolContract(compactResponse, "$.data.list[0]", 13);

        ResultActions compactSearchResponse = mockMvc.perform(get("/api/v1/tools/search")
                        .param("keyword", "safe public description")
                        .param("view", "compact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].toolCode").value("public_secret_guard_tool"))
                .andExpect(jsonPath("$.data.list[0].cardMedia").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].frontendStyle").doesNotExist());
        assertPublicToolContract(compactSearchResponse, "$.data.list[0]", 13);

        String summaryCacheKey = CacheNamespaces.toolList(7, "summary", "same-query");
        String compactCacheKey = CacheNamespaces.toolList(7, "compact", "same-query");
        assertThat(summaryCacheKey)
                .contains("tool:list:v3:summary")
                .isNotEqualTo(compactCacheKey);
        assertThat(compactCacheKey).contains("tool:list:v3:compact");

        ResultActions detailResponse = mockMvc.perform(get("/api/v1/tools/public_secret_guard_tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("public_secret_guard_tool"))
                .andExpect(jsonPath("$.data.modelDisplayName").value("Public Contract Model"))
                .andExpect(jsonPath("$.data.frontendStyle.heroTitle").value("Safe Hero"))
                .andExpect(jsonPath("$.data.frontendStyle.demoThumbnails[1]").value("https://cdn.example.com/two.webp"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("productName"))
                .andExpect(jsonPath("$.data.fields[0].fieldType").value("text"))
                .andExpect(jsonPath("$.data.fields[0].options.core").value(true))
                .andExpect(jsonPath("$.data.fields[0].options.maxCount").value(4))
                .andExpect(jsonPath("$.data.fields[0].options.baseUrl").doesNotExist())
                .andExpect(jsonPath("$.data.fields[0].options.engineSecrets").doesNotExist())
                .andExpect(jsonPath("$.data.fields[0].optionsJson").doesNotExist())
                .andExpect(jsonPath("$.data.fields[0].agentFillStrategy").doesNotExist())
                .andExpect(jsonPath("$.data.fields[0].riskLevel").doesNotExist())
                .andExpect(jsonPath("$.data.integration").doesNotExist())
                .andExpect(jsonPath("$.data.workflow").doesNotExist());
        assertPublicToolContract(detailResponse, "$.data", 15);

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(toolId.intValue()))
                .andExpect(jsonPath("$.data.categoryId").value(2))
                .andExpect(jsonPath("$.data.status").value("ONLINE"))
                .andExpect(jsonPath("$.data.modelConfigId").value(modelConfigId.intValue()))
                .andExpect(jsonPath("$.data.modelConfigName").value("Public Contract Model"))
                .andExpect(jsonPath("$.data.modelName").value("private/provider-model-route"))
                .andExpect(jsonPath("$.data.executionHandler").value("TEXT_GENERATION"))
                .andExpect(jsonPath("$.data.executionMode").value("DIRECT"))
                .andExpect(jsonPath("$.data.billingMode").value("FIXED"))
                .andExpect(jsonPath("$.data.agentSurfaceEnabled").value(false))
                .andExpect(jsonPath("$.data.configNote").exists());
    }

    @Test
    void mediaToolRejectsMismatchedModelWhenSavingDraft() throws Exception {
        String adminToken = loginAdmin();
        Long textOnlyModelId = createTextOnlyModelConfig(adminToken);

        mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "draft_image_without_model",
                                  "toolName": "Draft Image Without Model",
                                  "categoryId": 1,
                                  "description": "can be configured before model binding",
                                  "toolType": "IMAGE_TO_IMAGE",
                                  "inputModality": "IMAGE",
                                  "outputModality": "IMAGE",
                                  "executionHandler": "IMAGE_GENERATION",
                                  "modelConfigId": %d,
                                  "estimatedCreditCost": 6
                                }
                                """.formatted(textOnlyModelId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("model config does not support required capabilities [IMAGE_GENERATION]"));
    }

    @Test
    void adminCanCreateMinimalImageToolWithoutCategoryAndGetsUploadField() throws Exception {
        String adminToken = loginAdmin();

        String createResponse = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "simple_background_remover",
                                  "toolName": "背景去除器",
                                  "description": "上传图片后自动去除背景",
                                  "coverUrl": "/generated/tool-covers/background-remover.webp",
                                  "toolType": "IMAGE_TO_IMAGE",
                                  "inputModality": "IMAGE",
                                  "outputModality": "IMAGE",
                                  "executionHandler": "IMAGE_GENERATION",
                                  "estimatedCreditCost": 3,
                                  "configNote": "<!-- ai-tool-runtime:{\\"toolKind\\":\\"image\\",\\"adminPrompt\\":\\"Remove the background from {{sourceImageUrl}} and return a transparent PNG.\\",\\"userInputs\\":[{\\"fieldKey\\":\\"sourceImageUrl\\",\\"fieldName\\":\\"上传图片\\",\\"fieldType\\":\\"image_upload\\",\\"required\\":true}]} -->"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.categoryId").doesNotExist())
                .andExpect(jsonPath("$.data.toolKind").value("image"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long toolId = Long.parseLong(createResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("sourceImageUrl"))
                .andExpect(jsonPath("$.data.fields[0].fieldType").value("image_upload"))
                .andExpect(jsonPath("$.data.fields[0].required").value(true));
    }

    private void assertPublicToolContract(ResultActions response, String path, int fieldCount) throws Exception {
        response
                .andExpect(jsonPath(path, aMapWithSize(fieldCount)))
                .andExpect(jsonPath(path + ".sortOrder").doesNotExist())
                .andExpect(jsonPath(path + ".id").doesNotExist())
                .andExpect(jsonPath(path + ".categoryId").doesNotExist())
                .andExpect(jsonPath(path + ".configNote").doesNotExist())
                .andExpect(jsonPath(path + ".status").doesNotExist())
                .andExpect(jsonPath(path + ".modelConfigId").doesNotExist())
                .andExpect(jsonPath(path + ".modelConfigName").doesNotExist())
                .andExpect(jsonPath(path + ".modelName").doesNotExist())
                .andExpect(jsonPath(path + ".executionHandler").doesNotExist())
                .andExpect(jsonPath(path + ".executionMode").doesNotExist())
                .andExpect(jsonPath(path + ".billingMode").doesNotExist())
                .andExpect(jsonPath(path + ".agentSurfaceEnabled").doesNotExist())
                .andExpect(jsonPath(path + ".workflowConfigured").doesNotExist())
                .andExpect(jsonPath(path + ".workflowExecutionEnabled").doesNotExist())
                .andExpect(jsonPath(path + ".publishedWorkflowVersionId").doesNotExist())
                .andExpect(jsonPath(path + ".workflowUsable").doesNotExist());
    }

    private Long createPublicContractModelConfig(String adminToken) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Public Contract Model",
                                  "configCode": "public_tool_contract_model",
                                  "provider": "minimax",
                                  "modelName": "private/provider-model-route",
                                  "baseUrl": "https://private-upstream.example.com/v1",
                                  "apiKey": "private-api-key",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "TOKEN_PER_M",
                                  "unitPrice": 0,
                                  "capabilities": ["TEXT_GENERATION"],
                                  "enabled": true,
                                  "agentEnabled": true,
                                  "isDefault": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createTextOnlyModelConfig(String adminToken) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Text Only Model",
                                  "configCode": "text_only_for_publish_guard",
                                  "provider": "minimax",
                                  "modelName": "MiniMax-M2.7",
                                  "baseUrl": "https://api.minimaxi.com/v1",
                                  "apiKey": "fake-key",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "TOKEN_PER_M",
                                  "unitPrice": 0,
                                  "capabilities": ["TEXT_GENERATION"],
                                  "enabled": true,
                                  "agentEnabled": true,
                                  "isDefault": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createImageModelConfig(String adminToken) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Image Model For Public Tool",
                                  "configCode": "image_for_public_secret_guard",
                                  "provider": "siliconflow_images",
                                  "modelName": "Tongyi-MAI/Z-Image-Turbo",
                                  "baseUrl": "https://api.siliconflow.cn",
                                  "apiKey": "fake-key",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "PER_CALL",
                                  "unitPrice": 0,
                                  "capabilities": ["IMAGE_GENERATION"],
                                  "enabled": true,
                                  "agentEnabled": false,
                                  "isDefault": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
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
