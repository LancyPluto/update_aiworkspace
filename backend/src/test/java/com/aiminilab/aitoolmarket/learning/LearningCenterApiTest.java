package com.aiminilab.aitoolmarket.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:learning_center_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.generated-media-dir=target/test-generated-learning-center"
})
class LearningCenterApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearLearningCenterData() {
        jdbcTemplate.update("DELETE FROM learning_tutorials");
        jdbcTemplate.update("DELETE FROM learning_categories");
    }

    @Test
    void adminCrudIsSortedAndUserOnlySeesEnabledContent() throws Exception {
        String token = loginAdmin();
        long advancedId = createCategory(token, "第二阶段-进阶课程", 20, true);
        long basicId = createCategory(token, "第一阶段-基础课程", 10, true);
        long hiddenId = createCategory(token, "隐藏阶段", 1, false);

        long hiddenTutorialId = createTutorial(token, hiddenId, "隐藏分类课程", 1, true, "https://cdn.example.com/hidden.mp4");
        long visibleTutorialId = createTutorial(token, basicId, "认识 AI 工作台", 2, true, "https://cdn.example.com/basic.mp4");
        createTutorial(token, advancedId, "已下线课程", 1, false, "https://cdn.example.com/offline.mp4");

        mockMvc.perform(put("/api/admin/v1/learning-center/categories/{id}", basicId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "第一阶段-基础课程", "sortOrder": 10, "enabled": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("第一阶段-基础课程"));
        mockMvc.perform(put("/api/admin/v1/learning-center/tutorials/{id}", visibleTutorialId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId": %d, "title": "认识 AI 工作台", "summary": "更新后的简介",
                                 "coverImageUrl": "https://cdn.example.com/cover.webp",
                                 "videoUrl": "https://cdn.example.com/basic.mp4", "sortOrder": 2, "enabled": true}
                                """.formatted(basicId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("更新后的简介"));

        mockMvc.perform(get("/api/admin/v1/learning-center/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("隐藏阶段"))
                .andExpect(jsonPath("$.data[1].name").value("第一阶段-基础课程"));

        mockMvc.perform(get("/api/v1/learning-center")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(2))
                .andExpect(jsonPath("$.data.categories[0].name").value("第一阶段-基础课程"))
                .andExpect(jsonPath("$.data.categories[0].tutorials[0].id").value(visibleTutorialId))
                .andExpect(jsonPath("$.data.categories[1].tutorials.length()").value(0))
                .andExpect(jsonPath("$.data.teacherContact").exists());

        mockMvc.perform(delete("/api/admin/v1/learning-center/categories/{id}", hiddenId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该分类下仍有教程，请先移动或删除教程"));

        mockMvc.perform(delete("/api/admin/v1/learning-center/tutorials/{id}", hiddenTutorialId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/v1/learning-center/categories/{id}", hiddenId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void validationUploadAndAuthorizationAreEnforced() throws Exception {
        mockMvc.perform(get("/api/v1/learning-center"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/v1/learning-center/categories"))
                .andExpect(status().isUnauthorized());

        String token = loginAdmin();
        long categoryId = createCategory(token, "URL 校验", 30, true);
        mockMvc.perform(post("/api/admin/v1/learning-center/tutorials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId": %d, "title": "危险地址", "summary": "",
                                 "coverImageUrl": "", "videoUrl": "javascript:alert(1)",
                                 "sortOrder": 0, "enabled": true}
                                """.formatted(categoryId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("视频地址必须是有效的 HTTP/HTTPS 地址"));

        MockMultipartFile invalid = new MockMultipartFile("file", "cover.txt", "text/plain", "bad".getBytes());
        mockMvc.perform(multipart("/api/admin/v1/learning-center/covers/upload")
                        .file(invalid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));

        MockMultipartFile disguised = new MockMultipartFile(
                "file", "fake.jpg", "text/plain", "not-an-image".getBytes());
        mockMvc.perform(multipart("/api/admin/v1/learning-center/covers/upload")
                        .file(disguised)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
    }

    private long createCategory(String token, String name, int sortOrder, boolean enabled) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/learning-center/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "sortOrder": %d, "enabled": %s}
                                """.formatted(name, sortOrder, enabled)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private long createTutorial(String token, long categoryId, String title, int sortOrder,
                                boolean enabled, String videoUrl) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/learning-center/tutorials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId": %d, "title": "%s", "summary": "课程简介",
                                 "coverImageUrl": "https://cdn.example.com/cover.webp", "videoUrl": "%s",
                                 "sortOrder": %d, "enabled": %s}
                                """.formatted(categoryId, title, videoUrl, sortOrder, enabled)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private String loginAdmin() throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account": "admin", "password": "123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
    }
}
