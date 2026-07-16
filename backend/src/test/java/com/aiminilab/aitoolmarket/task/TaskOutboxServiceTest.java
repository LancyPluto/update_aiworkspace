package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.task.service.TaskQueuePublisher;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.aiminilab.aitoolmarket.task.service.impl.TaskIdempotencyRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:task_outbox_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=250",
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

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskIdempotencyRecoveryService taskIdempotencyRecoveryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

        Mockito.when(taskQueuePublisher.publish(eq(taskId), anyString())).thenReturn(false, true);

        assertThat(taskOutboxService.dispatchPending(10)).isZero();
        assertThat(outboxStatus(taskId)).isEqualTo("PENDING");
        assertThat(outboxRetryCount(taskId)).isEqualTo(1);

        jdbcTemplate.update("UPDATE task_outbox_events SET next_retry_at = CURRENT_TIMESTAMP WHERE task_id = ?", taskId);
        assertThat(taskOutboxService.dispatchPending(10)).isEqualTo(1);
        assertThat(outboxStatus(taskId)).isEqualTo("SENT");
        Mockito.verify(taskQueuePublisher, Mockito.times(2)).publish(eq(taskId), anyString());
    }

    @Test
    void repeatedPublishFailuresMoveEventToDeadAfterMaxRetries() throws Exception {
        String userToken = prepareOnlineTool("outbox_dead_tool", 4);
        Long taskId = createTask(userToken, "outbox_dead_tool", "outbox-dead-request");

        Mockito.when(taskQueuePublisher.publish(eq(taskId), anyString())).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("UPDATE task_outbox_events SET next_retry_at = CURRENT_TIMESTAMP WHERE task_id = ?", taskId);
            taskOutboxService.dispatchPending(10);
        }

        assertThat(outboxStatus(taskId)).isEqualTo("DEAD");
        assertThat(outboxRetryCount(taskId)).isEqualTo(5);

        jdbcTemplate.update("UPDATE task_outbox_events SET next_retry_at = CURRENT_TIMESTAMP WHERE task_id = ?", taskId);
        taskOutboxService.dispatchPending(10);
        Mockito.verify(taskQueuePublisher, Mockito.times(5)).publish(eq(taskId), anyString());
    }

    @Test
    void duplicateCreateWithSameIdempotencyKeyDoesNotCreateDuplicateOutboxEvent() throws Exception {
        String userToken = prepareOnlineTool("outbox_idempotent_tool", 3);

        Long firstTaskId = createTask(userToken, "outbox_idempotent_tool", "outbox-idempotent-request");
        Long secondTaskId = createTask(userToken, "outbox_idempotent_tool", "outbox-idempotent-request");

        assertThat(secondTaskId).isEqualTo(firstTaskId);
        assertThat(countOutboxEvents(firstTaskId)).isEqualTo(1);
    }

    @Test
    void concurrentRegularTaskCreationReturnsOneStableTask() throws Exception {
        prepareOnlineTool("outbox_concurrent_regular_tool", 3);
        CreateTaskRequest request = request(
                "outbox_concurrent_regular_tool",
                "outbox-concurrent-regular-request"
        );

        ConcurrentTasks created = createConcurrently(request, false);

        assertStableSingleCreation(created, request.clientRequestId());
    }

    @Test
    void concurrentAgentTaskCreationReturnsOneStableTask() throws Exception {
        prepareOnlineTool("outbox_concurrent_agent_tool", 3);
        CreateTaskRequest request = request(
                "outbox_concurrent_agent_tool",
                "outbox-concurrent-agent-request"
        );

        ConcurrentTasks created = createConcurrently(request, true);

        assertStableSingleCreation(created, request.clientRequestId());
    }

    @Test
    void deletedTaskStillOwnsItsIdempotencyKey() throws Exception {
        prepareOnlineTool("outbox_deleted_idempotent_tool", 3);
        CreateTaskRequest request = request(
                "outbox_deleted_idempotent_tool",
                "outbox-deleted-idempotent-request"
        );
        TaskStatusResponse first = taskService.create(1L, request);
        jdbcTemplate.update("UPDATE ai_tasks SET user_deleted = 1 WHERE id = ?", first.taskId());

        TaskStatusResponse repeated = taskService.create(1L, request);

        assertThat(repeated.taskId()).isEqualTo(first.taskId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE user_id = 1 AND idempotency_key = ?",
                Integer.class,
                request.clientRequestId()
        )).isEqualTo(1);
    }

    @Test
    void deletedTaskIdempotencyKeyCannotBeReusedByAnotherTool() throws Exception {
        prepareOnlineTool("outbox_deleted_original_tool", 3);
        prepareOnlineTool("outbox_deleted_conflicting_tool", 3);
        String requestId = "outbox-deleted-conflict-request";
        TaskStatusResponse first = taskService.create(
                1L,
                request("outbox_deleted_original_tool", requestId)
        );
        jdbcTemplate.update("UPDATE ai_tasks SET user_deleted = 1 WHERE id = ?", first.taskId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskService.create(
                1L,
                request("outbox_deleted_conflicting_tool", requestId)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void recoveryReadDoesNotWaitForSuspendedOuterTransactionLock() throws Exception {
        String toolCode = "outbox_recovery_nonlocking_tool";
        String requestId = "outbox-recovery-nonlocking-request";
        prepareOnlineTool(toolCode, 3);
        TaskStatusResponse original = taskService.create(1L, request(toolCode, requestId));
        Long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?",
                Long.class,
                toolCode
        );

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TaskStatusResponse recovered = outer.execute(status -> {
            jdbcTemplate.queryForObject(
                    "SELECT id FROM ai_tasks WHERE id = ? FOR UPDATE",
                    Long.class,
                    original.taskId()
            );
            return taskIdempotencyRecoveryService.recover(
                    1L,
                    requestId,
                    toolId,
                    new DuplicateKeyException("simulated duplicate task")
            );
        });

        assertThat(recovered).isNotNull();
        assertThat(recovered.taskId()).isEqualTo(original.taskId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankIdempotencyKeyCreatesIndependentTasksWithNullDatabaseKeys(String clientRequestId) throws Exception {
        String toolCode = clientRequestId.isEmpty()
                ? "outbox_empty_idempotency_tool"
                : "outbox_whitespace_idempotency_tool";
        prepareOnlineTool(toolCode, 3);
        CreateTaskRequest request = request(toolCode, clientRequestId);

        TaskStatusResponse first = taskService.create(1L, request);
        TaskStatusResponse second = taskService.create(1L, request);

        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM ai_tasks WHERE id = ?",
                String.class,
                first.taskId()
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM ai_tasks WHERE id = ?",
                String.class,
                second.taskId()
        )).isNull();
    }

    @Test
    void idempotencyToolComparisonIgnoresToolCodeCase() throws Exception {
        String toolCode = "outbox_case_insensitive_tool";
        String requestId = "outbox-case-insensitive-request";
        prepareOnlineTool(toolCode, 3);

        TaskStatusResponse first = taskService.create(1L, request(toolCode, requestId));
        TaskStatusResponse repeated = taskService.create(1L, request(toolCode.toUpperCase(), requestId));

        assertThat(repeated.taskId()).isEqualTo(first.taskId());
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

    private CreateTaskRequest request(String toolCode, String clientRequestId) {
        return new CreateTaskRequest(
                toolCode,
                objectMapper.createObjectNode().put("productName", "Idempotency test"),
                clientRequestId,
                null,
                null
        );
    }

    private ConcurrentTasks createConcurrently(CreateTaskRequest request, boolean agentCreate) throws Exception {
        CountDownLatch snapshotsEstablished = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TaskStatusResponse> first = executor.submit(
                    () -> createAfterRepeatableReadSnapshot(request, agentCreate, snapshotsEstablished)
            );
            Future<TaskStatusResponse> second = executor.submit(
                    () -> createAfterRepeatableReadSnapshot(request, agentCreate, snapshotsEstablished)
            );
            return new ConcurrentTasks(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private TaskStatusResponse createAfterRepeatableReadSnapshot(CreateTaskRequest request,
                                                                  boolean agentCreate,
                                                                  CountDownLatch snapshotsEstablished) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_tasks WHERE user_id = 1 AND idempotency_key = ?",
                    Integer.class,
                    request.clientRequestId()
            )).isZero();
            snapshotsEstablished.countDown();
            await(snapshotsEstablished);
            return agentCreate
                    ? taskService.createForAgentTool(1L, request)
                    : taskService.create(1L, request);
        });
    }

    private void assertStableSingleCreation(ConcurrentTasks created, String requestId) {
        assertThat(created.second().taskId()).isEqualTo(created.first().taskId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE user_id = 1 AND idempotency_key = ?",
                Integer.class,
                requestId
        )).isEqualTo(1);
        assertThat(countOutboxEvents(created.first().taskId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE task_id = ? AND log_type = 'FREEZE'",
                Integer.class,
                created.first().taskId()
        )).isEqualTo(1);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out coordinating concurrent task creation");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted coordinating concurrent task creation", exception);
        }
    }

    private record ConcurrentTasks(TaskStatusResponse first, TaskStatusResponse second) {
    }
}
