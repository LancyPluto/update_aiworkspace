package com.aiminilab.aitoolmarket.community;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:community_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class CommunityApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommunityService communityService;

    @Autowired
    private TaskMapper taskMapper;

    @Test
    void autoPublishCreatesPostWhenUserPreferenceEnabled() {
        jdbcTemplate.update("UPDATE users SET auto_publish_assets = 1, prompt_public_by_default = 0 WHERE id = 2");
        long taskId = insertSuccessImageTask(2L, "auto_publish_on");
        AiTask task = taskMapper.findById(taskId).orElseThrow();

        communityService.autoPublishTask(task, "IMAGE", "{\"images\":[{\"url\":\"/generated/community-auto.png\"}]}");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE task_id = ? AND status = 'PUBLISHED'",
                Integer.class,
                taskId);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }

    @Test
    void autoPublishSkipsWhenUserPreferenceDisabled() {
        jdbcTemplate.update("UPDATE users SET auto_publish_assets = 0 WHERE id = 2");
        long taskId = insertSuccessImageTask(2L, "auto_publish_off");
        AiTask task = taskMapper.findById(taskId).orElseThrow();

        communityService.autoPublishTask(task, "IMAGE", "{\"images\":[{\"url\":\"/generated/community-skip.png\"}]}");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE task_id = ?",
                Integer.class,
                taskId);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    void manualPublishUsesPromptPublicByDefaultWhenRequestOmitsPromptVisible() throws Exception {
        jdbcTemplate.update("UPDATE users SET prompt_public_by_default = 1 WHERE id = 2");
        String userToken = loginUser();
        long taskId = insertSuccessImageTask(2L, "prompt_default_on");

        mockMvc.perform(post("/api/v1/community/posts")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": %d,
                                  "title": "默认公开提示词作品"
                                }
                                """.formatted(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promptVisible").value(true));
    }

    @Test
    void hiddenRejectedAndPendingPostsAreNotVisibleInPublicEntrances() throws Exception {
        long approvedId = insertPost("PUBLISHED", "APPROVED", "产品图生成", true);
        long hiddenId = insertPost("HIDDEN", "APPROVED", "隐藏作品", true);
        long rejectedId = insertPost("PUBLISHED", "REJECTED", "驳回作品", true);
        long pendingId = insertPost("PUBLISHED", "PENDING", "待审作品", true);

        mockMvc.perform(get("/api/v1/community/search")
                        .param("keyword", "作品")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[*].id", everyItem(not((int) hiddenId))))
                .andExpect(jsonPath("$.data.list[*].id", everyItem(not((int) rejectedId))))
                .andExpect(jsonPath("$.data.list[*].id", everyItem(not((int) pendingId))));

        mockMvc.perform(get("/api/v1/community/posts/{postId}", approvedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) approvedId));

        mockMvc.perform(get("/api/v1/community/posts/{postId}", hiddenId))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/community/posts/{postId}", rejectedId))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/community/posts/{postId}", pendingId))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void promptHiddenPostDoesNotReturnPrompt() throws Exception {
        long postId = insertPost("PUBLISHED", "APPROVED", "隐藏 Prompt", false);

        mockMvc.perform(get("/api/v1/community/posts/{postId}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promptVisible").value(false))
                .andExpect(jsonPath("$.data.prompt").value(nullValue()));
    }

    @Test
    void addingSamePostToInspirationCollectionIsIdempotent() throws Exception {
        String userToken = loginUser();
        long postId = insertPost("PUBLISHED", "APPROVED", "收藏灵感", true);

        String collectionResponse = mockMvc.perform(get("/api/v1/community/collections")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].defaultCollection").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long collectionId = Long.parseLong(collectionResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        String body = "{\"postId\":%d}".formatted(postId);
        mockMvc.perform(post("/api/v1/community/collections/{collectionId}/items", collectionId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemCount").value(1));

        mockMvc.perform(post("/api/v1/community/collections/{collectionId}/items", collectionId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemCount").value(1));
    }

    @Test
    void sameStyleRecordsCountAndEvent() throws Exception {
        String userToken = loginUser();
        long postId = insertPost("PUBLISHED", "APPROVED", "同款归因", true);

        mockMvc.perform(post("/api/v1/community/posts/{postId}/same-style", postId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sameStyleCount").value(1));

        Integer eventCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM community_events
                WHERE post_id = ? AND event_type = 'same_style_click'
                """, Integer.class, postId);
        org.junit.jupiter.api.Assertions.assertEquals(1, eventCount);
    }

    @Test
    void publicPostDetailReturnsViewerInteractionFlagsWhenAuthenticated() throws Exception {
        String userToken = loginUser();
        long postId = insertPost("PUBLISHED", "APPROVED", "互动状态", true);

        mockMvc.perform(get("/api/v1/community/posts/{postId}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.favorited").value(false));

        mockMvc.perform(post("/api/v1/community/posts/{postId}/like", postId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true));

        mockMvc.perform(post("/api/v1/community/posts/{postId}/favorite", postId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true));

        mockMvc.perform(get("/api/v1/community/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.favorited").value(true));

        mockMvc.perform(get("/api/v1/community/search")
                        .param("keyword", "互动状态")
                        .param("pageSize", "20")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].id").value((int) postId))
                .andExpect(jsonPath("$.data.list[0].liked").value(true))
                .andExpect(jsonPath("$.data.list[0].favorited").value(true));
    }

    @Test
    void topicEntriesOnlyComeFromVisibleApprovedPosts() throws Exception {
        long visibleId = insertPost("PUBLISHED", "APPROVED", "Visible topic post", true);
        long hiddenId = insertPost("HIDDEN", "APPROVED", "Hidden topic post", true);
        long rejectedId = insertPost("PUBLISHED", "REJECTED", "Rejected topic post", true);
        jdbcTemplate.update("UPDATE community_posts SET topic = ? WHERE id = ?", "产品图生成", visibleId);
        jdbcTemplate.update("UPDATE community_posts SET topic = ? WHERE id = ?", "不应展示", hiddenId);
        jdbcTemplate.update("UPDATE community_posts SET topic = ? WHERE id = ?", "驳回话题", rejectedId);

        mockMvc.perform(get("/api/v1/community/topics").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", hasItem("产品图生成")))
                .andExpect(jsonPath("$.data[*].name", everyItem(not("不应展示"))))
                .andExpect(jsonPath("$.data[*].name", everyItem(not("驳回话题"))));
    }

    private String loginUser() throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "user1",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return AuthTestTokens.userJwtFrom(result);
    }

    private long insertSuccessImageTask(long userId, String suffix) {
        Long toolId = jdbcTemplate.query(
                "SELECT id FROM ai_tools WHERE output_modality = 'IMAGE' ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (toolId == null) {
            Long categoryId = jdbcTemplate.query(
                    "SELECT id FROM tool_categories ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (categoryId == null) {
                jdbcTemplate.update("""
                        INSERT INTO tool_categories (category_code, category_name, sort_order, status)
                        VALUES ('community_test', '测试分类', 1, 'ACTIVE')
                        """);
                categoryId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM tool_categories", Long.class);
            }
            jdbcTemplate.update("""
                    INSERT INTO ai_tools (
                      tool_code, tool_name, category_id, status, tool_type, execution_handler,
                      input_modality, output_modality, estimated_credit_cost
                    )
                    VALUES (?, '图片工具', ?, 'ONLINE', 'IMAGE_GENERATION', 'IMAGE_GENERATION', 'TEXT', 'IMAGE', 10)
                    """, "community_image_" + suffix, categoryId);
            toolId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM ai_tools", Long.class);
        }
        String taskNo = "COMM-" + suffix + "-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO ai_tasks (
                  task_no, user_id, tool_id, status, progress, params_json, estimated_credit_cost, finished_at
                )
                VALUES (?, ?, ?, 'SUCCESS', 100, '{"prompt":"公开 prompt 测试"}', 10, CURRENT_TIMESTAMP)
                """, taskNo, userId, toolId);
        long taskId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM ai_tasks", Long.class);
        jdbcTemplate.update("""
                INSERT INTO ai_result_resources (task_id, user_id, resource_type, content_text)
                VALUES (?, ?, 'IMAGE', ?)
                """, taskId, userId, "{\"images\":[{\"url\":\"/generated/community-task.png\"}]}");
        return taskId;
    }

    private long insertPost(String status, String auditStatus, String title, boolean promptVisible) {
        Long nextTaskId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(task_id), 1000) + 1 FROM community_posts", Long.class);
        jdbcTemplate.update("""
                INSERT INTO community_posts (
                  user_id, task_id, modality, cover_url, title, description, prompt_visible,
                  prompt_snapshot, tool_code, tool_name, status, audit_status, view_count,
                  like_count, favorite_count, same_style_count, quality_score
                )
                VALUES (
                  2, ?, 'IMAGE', '/generated/community-test.png', ?, '社区接口测试作品',
                  ?, '公开 prompt 内容', 'image_tool', '图片工具', ?, ?, 0, 0, 0, 0, 0
                )
                """, nextTaskId, title, promptVisible ? 1 : 0, status, auditStatus);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM community_posts", Long.class);
    }
}
