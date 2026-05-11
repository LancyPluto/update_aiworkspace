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
        "spring.datasource.url=jdbc:h2:mem:tool_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
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
                                  "coverUrl": "",
                                  "estimatedCreditCost": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
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
                                  "coverUrl": "",
                                  "estimatedCreditCost": 12
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolName").value("小红书文案助手"))
                .andExpect(jsonPath("$.data.estimatedCreditCost").value(12));

        mockMvc.perform(post("/api/admin/v1/tools/" + toolId + "/publish")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"));

        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].toolCode").value("xiaohongshu_copywriting"))
                .andExpect(jsonPath("$.data.list[0].toolName").value("小红书文案助手"));

        mockMvc.perform(get("/api/v1/tools/xiaohongshu_copywriting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("xiaohongshu_copywriting"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("productName"));

        mockMvc.perform(post("/api/admin/v1/tools/" + toolId + "/offline")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFFLINE"));

        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
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
}
