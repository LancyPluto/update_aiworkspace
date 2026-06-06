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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_tool_template_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AdminToolTemplateApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void systemTemplatesAreSeededAndCanApplyToTool() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        mockMvc.perform(get("/api/admin/v1/tool-templates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.templateCode=='image_generation_default')]").exists())
                .andExpect(jsonPath("$.data[?(@.templateCode=='text_to_speech_default')]").exists())
                .andExpect(jsonPath("$.data[?(@.templateCode=='music_generation_default')]").exists());

        mockMvc.perform(get("/api/admin/v1/tool-templates/image_generation_default")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("prompt"));

        mockMvc.perform(get("/api/admin/v1/tool-templates/text_to_speech_default")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolType").value("TEXT_TO_SPEECH"))
                .andExpect(jsonPath("$.data.executionHandler").value("TEXT_TO_SPEECH"))
                .andExpect(jsonPath("$.data.outputModality").value("AUDIO"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("text"));

        mockMvc.perform(get("/api/admin/v1/tool-templates/music_generation_default")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolType").value("MUSIC_GENERATION"))
                .andExpect(jsonPath("$.data.executionHandler").value("MUSIC_GENERATION"))
                .andExpect(jsonPath("$.data.outputModality").value("AUDIO"))
                .andExpect(jsonPath("$.data.fields[?(@.fieldKey=='prompt')]").exists())
                .andExpect(jsonPath("$.data.fields[?(@.fieldKey=='customMode')]").exists())
                .andExpect(jsonPath("$.data.fields[?(@.fieldKey=='generationType')]").exists())
                .andExpect(jsonPath("$.data.fields[?(@.fieldKey=='referenceAudio')]").exists())
                .andExpect(jsonPath("$.data.fields[?(@.fieldKey=='personaId')]").exists());

        Long toolId = createToolWithTemplate(adminToken, "tpl_image_tool", "image_generation_default");

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/fields", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fieldKey").value("prompt"));
    }

    private Long createToolWithTemplate(String adminToken, String toolCode, String templateCode) throws Exception {
        String body = """
                {
                  "toolCode": "%s",
                  "toolName": "Template Tool",
                  "categoryId": 1,
                  "description": "from template",
                  "estimatedCreditCost": 3,
                  "templateCode": "%s"
                }
                """.formatted(toolCode, templateCode);
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
        return AuthTestTokens.adminJwtFrom(result);
    }
}
