package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:task_outbox_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class TaskOutboxServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskOutboxService taskOutboxService;

    @MockBean
    private TaskQueuePublisher taskQueuePublisher;

    @Test
    void taskCreationWritesPendingOutboxEventWithoutPublishingImmediately() throws Exception {
        String userToken = prepareOnlineTool("outbox_creation_tool", 5);

        Long taskId = createTask(userToken, "outbox_creation_tool", "outbox-create-request");

        assertThat(countOutboxEvents(taskId)).isEqualTo(1);
        assertThat(outboxStatus(taskId)).isEqualTo("PENDING");
        Mockito.verifyNoInteractions(taskQueuePublisher);
    }

    @Test
    void redisPublishFailureKeepsEventPendingAndRetryLaterSendsIt() throws Exception {
        String userToken = prepareOnlineTool("outbox_retry_tool", 4);
        Long taskId = createTask(userToken, "outbox_retry_tool", "outbox-retry-request");

        Mockito.when(taskQueuePublisher.publish(taskId)).thenReturn(false, true);

        assertThat(taskOutboxService.dispatchPending(10)).isZero();
        assertThat(outboxStatus(taskId)).isEqualTo("PENDING");
        assertThat(outboxRetryCount(taskId)).isEqualTo(1);

        jdbcTemplate.update("UPDATE task_outbox_events SET next_retry_at = CURRENT_TIMESTAMP WHERE task_id = ?", taskId);
        assertThat(taskOutboxService.dispatchPending(10)).isEqualTo(1);
        assertThat(outboxStatus(taskId)).isEqualTo("SENT");
        Mockito.verify(taskQueuePublisher, Mockito.times(2)).publish(taskId);
    }

    @Test
    void duplicateCreateWithSameIdempotencyKeyDoesNotCreateDuplicateOutboxEvent() throws Exception {
        String userToken = prepareOnlineTool("outbox_idempotent_tool", 3);

        Long firstTaskId = createTask(userToken, "outbox_idempotent_tool", "outbox-idempotent-request");
        Long secondTaskId = createTask(userToken, "outbox_idempotent_tool", "outbox-idempotent-request");

        assertThat(secondTaskId).isEqualTo(firstTaskId);
        assertThat(countOutboxEvents(firstTaskId)).isEqualTo(1);
    }

    private String prepareOnlineTool(String toolCode, int estimatedCreditCost) throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, toolCode, estimatedCreditCost);
        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        return login("/api/v1/auth/login", "user1");
    }

    private String login(String path, String account) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "123456"
                                }
                                """.formatted(account)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long createTool(String adminToken, String toolCode, int estimatedCreditCost) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "toolName": "%s",
                                  "categoryId": 1,
                                  "description": "Outbox test tool",
                                  "coverUrl": "",
                                  "estimatedCreditCost": %d
                                }
                                """.formatted(toolCode, toolCode, estimatedCreditCost)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createTask(String userToken, String toolCode, String clientRequestId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "params": {
                                    "productName": "Outbox Product",
                                    "targetCustomer": "Young users",
                                    "style": "planting"
                                  },
                                  "clientRequestId": "%s"
                                }
                                """.formatted(toolCode, clientRequestId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private int countOutboxEvents(Long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id = ?",
                Integer.class,
                taskId
        );
        return count == null ? 0 : count;
    }

    private String outboxStatus(Long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM task_outbox_events WHERE task_id = ?",
                String.class,
                taskId
        );
    }

    private int outboxRetryCount(Long taskId) {
        Integer retryCount = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM task_outbox_events WHERE task_id = ?",
                Integer.class,
                taskId
        );
        return retryCount == null ? 0 : retryCount;
    }
}
