package com.aiminilab.aitoolmarket.subject;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:subject_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class SubjectApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userCanCreateSubjectAndWorkerCanReadSyncContext() throws Exception {
        String userToken = login("/api/v1/auth/login", "user1");
        String subjectCode = createImageSubject(userToken, "测试主体", "角色主体");

        mockMvc.perform(signed(get("/api/internal/v1/subjects/{subjectCode}/sync-context", subjectCode), "GET",
                        "/api/internal/v1/subjects/%s/sync-context".formatted(subjectCode), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectCode").value(subjectCode))
                .andExpect(jsonPath("$.data.referenceType").value("image_refer"));

        String syncResultBody = """
                {
                  "syncStatus": "READY",
                  "syncTaskId": "task-001",
                  "upstreamElementId": "elem-001"
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/subjects/{subjectCode}/sync-result", subjectCode), "POST",
                        "/api/internal/v1/subjects/%s/sync-result".formatted(subjectCode), syncResultBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(syncResultBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/subjects")
                        .header("Authorization", "Bearer " + userToken)
                        .param("provider", "kling_video")
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].upstreamElementId", is("elem-001")));
    }

    @Test
    void createSubjectValidatesKlingElementLimits() throws Exception {
        String userToken = login("/api/v1/auth/login", "user1");
        ObjectNode referenceJson = objectMapper.createObjectNode();
        referenceJson.put("frontalImage", "https://example.com/front.jpg");
        referenceJson.putArray("referImages")
                .add("https://example.com/side-1.jpg")
                .add("https://example.com/side-2.jpg")
                .add("https://example.com/side-3.jpg")
                .add("https://example.com/side-4.jpg");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("displayName", "超过二十个字符的可灵主体名称会失败");
        body.put("description", "角色主体");
        body.put("referenceType", "image_refer");
        body.put("vendorAccountRef", "kling::test-default");
        body.set("referenceJson", referenceJson);

        mockMvc.perform(post("/api/v1/subjects")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));

        body.put("displayName", "测试主体");
        mockMvc.perform(post("/api/v1/subjects")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));
    }

    @Test
    void deleteSubjectSoftDeletesLocalRecord() throws Exception {
        String userToken = login("/api/v1/auth/login", "user1");
        String subjectCode = createImageSubject(userToken, "待删除主体", "角色主体");

        mockMvc.perform(delete("/api/v1/subjects/{subjectCode}", subjectCode)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/subjects/{subjectCode}", subjectCode)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private String createImageSubject(String userToken, String displayName, String description) throws Exception {
        ObjectNode referenceJson = objectMapper.createObjectNode();
        referenceJson.put("frontalImage", "https://example.com/front.jpg");
        referenceJson.putArray("referImages").add("https://example.com/side.jpg");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("displayName", displayName);
        body.put("description", description);
        body.put("referenceType", "image_refer");
        body.put("vendorAccountRef", "kling::test-default");
        body.set("referenceJson", referenceJson);

        String createResponse = mockMvc.perform(post("/api/v1/subjects")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.displayName").value(displayName))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(createResponse).path("data").path("subjectCode").asText();
    }

    private String login(String path, String account) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "123456"
                                }
                                """.formatted(account)))
                .andExpect(status().isOk())
                .andReturn();
        return AuthTestTokens.userJwtFrom(result);
    }
}
