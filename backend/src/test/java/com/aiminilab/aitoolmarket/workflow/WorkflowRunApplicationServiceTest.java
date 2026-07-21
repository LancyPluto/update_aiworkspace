package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRecoveryScheduler;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_run_application_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "workflow.runtime.enabled=true",
        "workflow.runtime.execution-enabled=true",
        "workflow.runtime.canary-percentage=100",
        "workflow.runtime.real-billing-enabled=true",
        "workflow.runtime.confirmation-enabled=true",
        "workflow.runtime.max-run-cost-credits=3",
        "workflow.runtime.max-user-daily-cost-credits=10000",
        "spring.task.scheduling.enabled=false"
})
class WorkflowRunApplicationServiceTest {

    private static final String TOOL_CODE = "workflow_application_test";
    private static final String NODES = """
            [
              {"id":"start","data":{"nodeDefType":"start","title":"开始"}},
              {"id":"output","data":{"nodeDefType":"video_output","title":"输出"}}
            ]
            """;
    private static final String EDGES = """
            [{"id":"edge-1","source":"start","target":"output"}]
            """;
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"prompt":{"type":"string"}},"required":[]}
            """;
    private static final String AWAITING_NODES = """
            [
              {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
              {"id":"review","data":{"nodeDefType":"user_input","title":"Review","parameters":{"fieldKey":"pinnedReview","stageLabel":"Pinned review"}}},
              {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
            ]
            """;
    private static final String REPLACEMENT_NODES = """
            [
              {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
              {"id":"review","data":{"nodeDefType":"llm_text","title":"Replacement worker"}},
              {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
            ]
            """;
    private static final String AWAITING_EDGES = """
            [
              {"id":"edge-1","source":"start","target":"review"},
              {"id":"edge-2","source":"review","target":"output"}
            ]
            """;

    @Autowired
    private WorkflowRunApplicationService service;

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WorkflowRuntimeGate runtimeGate;

    @MockBean
    private WorkflowRecoveryScheduler workflowRecoveryScheduler;

    private long workflowId;
    private long firstVersionId;
    private long toolId;

    @BeforeEach
    void setUpPublishedWorkflow() {
        runtimeGate.markReconciliationHealthyAfterFullScan(runtimeGate.reconciliationFailureGeneration());
        jdbcTemplate.update("DELETE FROM workflow_run_steps");
        jdbcTemplate.update("DELETE FROM workflow_runs");
        jdbcTemplate.update("DELETE FROM ai_tasks WHERE idempotency_key LIKE 'workflow-app-%'");
        jdbcTemplate.update("""
                DELETE FROM tool_workflow_versions
                WHERE workflow_id IN (
                  SELECT w.id FROM tool_workflows w
                  JOIN ai_tools t ON t.id = w.tool_id
                  WHERE t.tool_code = ?
                )
                """, TOOL_CODE);
        jdbcTemplate.update("""
                DELETE FROM tool_workflows
                WHERE tool_id IN (SELECT id FROM ai_tools WHERE tool_code = ?)
                """, TOOL_CODE);
        jdbcTemplate.update(
                "DELETE FROM ai_tools WHERE tool_code IN (?, ?)",
                TOOL_CODE,
                "workflow_application_other"
        );

        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, status,
                  estimated_credit_cost, execution_handler, execution_mode,
                  billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, 'Workflow Application Test', 1, 'test', 'ONLINE', 0,
                          'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 1, 0, 0)
                """, TOOL_CODE);
        toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?",
                Long.class,
                TOOL_CODE
        );
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, groups_json, config_json,
                  version, status, draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, 'application-test', ?, ?, NULL, '{}', 1, 'PUBLISHED', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, NODES, EDGES);
        workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ? AND workflow_name = 'application-test'",
                Long.class,
                toolId
        );
        firstVersionId = insertVersion(1, 1L);
        jdbcTemplate.update(
                "UPDATE tool_workflows SET published_version_id = ? WHERE id = ?",
                firstVersionId,
                workflowId
        );
    }

    @Test
    void sameUserAndClientRequestReturnsSameRootTaskAndPinnedVersion() throws Exception {
        CreateWorkflowRunCommand command = new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"城市夜景\"}"),
                "workflow-app-request-123",
                "AGENTS_PAGE",
                null
        );

        WorkflowRunCreated first = service.create(command);
        JsonNode persistedInput = readJsonDocument(jdbcTemplate.queryForObject(
                "SELECT input_json FROM workflow_runs WHERE id = ?",
                String.class,
                first.runId()
        ));
        assertThat(persistedInput.path("__workflowRequestIdentity").path("inputFingerprint").asText())
                .hasSize(64);
        WorkflowRunCreated repeated = service.create(command);

        long secondVersionId = insertVersion(2, 2L);
        jdbcTemplate.update(
                "UPDATE tool_workflows SET published_version_id = ?, version = 2, draft_revision = 2 WHERE id = ?",
                secondVersionId,
                workflowId
        );
        WorkflowRunCreated afterPublicationChanged = service.create(command);

        assertThat(repeated.rootTaskId()).isEqualTo(first.rootTaskId());
        assertThat(afterPublicationChanged.rootTaskId()).isEqualTo(first.rootTaskId());
        assertThat(repeated.runId()).isEqualTo(first.runId());
        assertThat(afterPublicationChanged.workflowVersionId()).isEqualTo(firstVersionId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE user_id = 1 AND idempotency_key = 'workflow-app-request-123'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = 1 AND client_request_id = 'workflow-app-request-123'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_run_steps WHERE run_id = ?",
                Integer.class,
                first.runId()
        )).isEqualTo(2);

        jdbcTemplate.update("UPDATE ai_tools SET status = 'OFFLINE', agent_surface_enabled = 0 WHERE id = ?", toolId);
        jdbcTemplate.update("UPDATE tool_workflows SET execution_enabled = 0 WHERE id = ?", workflowId);
        WorkflowRunCreated afterToolOffline = service.create(command);
        assertThat(afterToolOffline.runId()).isEqualTo(first.runId());
        assertThat(afterToolOffline.workflowVersionId()).isEqualTo(firstVersionId);
    }

    @Test
    void sameClientRequestRejectsDifferentOriginalInput() throws Exception {
        service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"first request\"}"),
                "workflow-app-input-conflict",
                "AGENTS_PAGE",
                null
        ));

        assertThatThrownBy(() -> service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"changed request\"}"),
                "workflow-app-input-conflict",
                "AGENTS_PAGE",
                null
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void sameClientRequestRejectsDifferentLaunchSource() throws Exception {
        service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"same request\"}"),
                "workflow-app-source-conflict",
                "AGENTS_PAGE",
                null
        ));

        assertThatThrownBy(() -> service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"same request\"}"),
                "workflow-app-source-conflict",
                "AGENT_CHAT",
                null
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void firstCreationRejectsOfflineDirectOrUnsupportedBillingToolState() {
        String[] unavailableUpdates = {
                "UPDATE ai_tools SET status = 'OFFLINE' WHERE id = ?",
                "UPDATE ai_tools SET execution_mode = 'DIRECT' WHERE id = ?",
                "UPDATE ai_tools SET billing_mode = 'FIXED' WHERE id = ?"
        };
        for (int index = 0; index < unavailableUpdates.length; index++) {
            jdbcTemplate.update("""
                    UPDATE ai_tools
                    SET status = 'ONLINE', execution_mode = 'WORKFLOW',
                        billing_mode = 'WORKFLOW_STEP', agent_surface_enabled = 1
                    WHERE id = ?
                    """, toolId);
            jdbcTemplate.update(unavailableUpdates[index], toolId);
            CreateWorkflowRunCommand command = new CreateWorkflowRunCommand(
                    1L,
                    TOOL_CODE,
                    objectMapper.createObjectNode().put("prompt", "blocked"),
                    "workflow-app-unavailable-" + index,
                    "AGENTS_PAGE",
                    null
            );
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND));
        }
    }

    @Test
    void validatesNewRunInputAgainstPinnedPublishedSchema() throws Exception {
        jdbcTemplate.update(
                "UPDATE tool_workflow_versions SET input_schema_snapshot_json = ? WHERE id = ?",
                """
                {"type":"object","properties":{"prompt":{"type":"string","enum":["allowed"]}},"required":["prompt"]}
                """,
                firstVersionId
        );

        CreateWorkflowRunCommand missing = new CreateWorkflowRunCommand(
                1L, TOOL_CODE, objectMapper.createObjectNode(),
                "workflow-app-schema-missing", "AGENTS_PAGE", null
        );
        assertThatThrownBy(() -> service.create(missing))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
                    assertThat(exception.getMessage()).contains("$.prompt is required");
                });

        CreateWorkflowRunCommand wrongType = new CreateWorkflowRunCommand(
                1L, TOOL_CODE, objectMapper.createObjectNode().put("prompt", 7),
                "workflow-app-schema-type", "AGENTS_PAGE", null
        );
        assertThatThrownBy(() -> service.create(wrongType))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
                    assertThat(exception.getMessage()).contains("$.prompt must be string");
                });

        WorkflowRunCreated created = service.create(new CreateWorkflowRunCommand(
                1L, TOOL_CODE, objectMapper.createObjectNode().put("prompt", "allowed"),
                "workflow-app-schema-valid", "AGENTS_PAGE", null
        ));
        assertThat(created.workflowVersionId()).isEqualTo(firstVersionId);
    }

    @Test
    void rejectsNewRunWhenPublishedInputSchemaIsInvalid() {
        jdbcTemplate.update(
                "UPDATE tool_workflow_versions SET input_schema_snapshot_json = '{}' WHERE id = ?",
                firstVersionId
        );
        CreateWorkflowRunCommand command = new CreateWorkflowRunCommand(
                1L, TOOL_CODE, objectMapper.createObjectNode(),
                "workflow-app-schema-invalid", "AGENTS_PAGE", null
        );

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_RUNTIME_BLOCKED);
                    assertThat(exception.getData()).isEqualTo(
                            java.util.Map.of("reason", "published_input_schema_invalid"));
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE client_request_id = 'workflow-app-schema-invalid'",
                Integer.class
        )).isZero();
    }

    @Test
    void agentCreationCanJoinCallerTransactionAndRollBackRootRunAndBindingTogether() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO agent_tool_calls(run_id, user_id, tool_code, status, arguments_json, started_at, created_at)
                VALUES (7001, 1, ?, 'RUNNING', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, TOOL_CODE);
        Long toolCallId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_tool_calls WHERE run_id = 7001 AND tool_code = ?",
                Long.class,
                TOOL_CODE
        );
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        WorkflowRunCreated created = transaction.execute(status -> {
            WorkflowRunCreated result = service.createInCurrentTransaction(new CreateWorkflowRunCommand(
                    1L,
                    TOOL_CODE,
                    objectMapper.createObjectNode().put("prompt", "rollback"),
                    "workflow-app-agent-rollback",
                    "AGENT_CHAT",
                    toolCallId
            ));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM agent_tool_calls WHERE id = ?",
                    String.class,
                    toolCallId
            )).isEqualTo("SUCCESS");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_run_events WHERE run_id = 7001 AND event_type = 'tool.finished'",
                    Integer.class
            )).isEqualTo(1);
            status.setRollbackOnly();
            return result;
        });

        assertThat(created).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE client_request_id = 'workflow-app-agent-rollback'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE idempotency_key = 'workflow-app-agent-rollback'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_tool_calls WHERE id = ?",
                String.class,
                toolCallId
        )).isEqualTo("RUNNING");
    }

    @Test
    void creationPinsTheDefaultExecutableWorkflowWhenAlternateIsNewer() throws Exception {
        jdbcTemplate.update(
                "UPDATE tool_workflows SET execution_enabled = 0 WHERE id = ?",
                workflowId
        );
        PublishedWorkflow defaultWorkflow = insertExecutableWorkflow("default");
        PublishedWorkflow alternateWorkflow = insertExecutableWorkflow("alternate");
        assertThat(alternateWorkflow.workflowId()).isGreaterThan(defaultWorkflow.workflowId());

        WorkflowRunCreated created = service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{}"),
                "workflow-app-canonical-default",
                "AGENTS_PAGE",
                null
        ));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT workflow_id, workflow_version_id FROM workflow_runs WHERE id = ?",
                created.runId()
        )).containsEntry("workflow_id", defaultWorkflow.workflowId())
                .containsEntry("workflow_version_id", defaultWorkflow.versionId());
        assertThat(created.workflowVersionId()).isEqualTo(defaultWorkflow.versionId());
    }

    @Test
    void firstCreationAdvancesInlineWorkflowToCompletion() throws Exception {
        WorkflowRunCreated created = service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"city at night\"}"),
                "workflow-app-advances",
                "AGENTS_PAGE",
                null
        ));

        assertThat(jdbcTemplate.queryForList(
                "SELECT node_id, status FROM workflow_run_steps WHERE run_id = ? ORDER BY sequence_no",
                created.runId()
        )).containsExactly(
                java.util.Map.of("node_id", "start", "status", "SUCCESS"),
                java.util.Map.of("node_id", "output", "status", "SUCCESS")
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                created.runId()
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?",
                String.class,
                created.rootTaskId()
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id = ?",
                Integer.class,
                created.rootTaskId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE task_id = ?",
                Integer.class,
                created.rootTaskId()
        )).isZero();
    }

    @Test
    void trustedOperationRunCreatesOnlyTheRequestedShotPipelineSteps() throws Exception {
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"script","data":{"nodeDefType":"llm_text","title":"Script","parameters":{"handlerKey":"comic.script"}}},
                  {"id":"review","data":{"nodeDefType":"user_confirm","title":"Review","parameters":{}}},
                  {"id":"shot-audio","data":{"nodeDefType":"tts_model","title":"Shot audio","parameters":{"handlerKey":"comic.shot_tts"}}},
                  {"id":"shot-keyframes","data":{"nodeDefType":"image_model","title":"Shot keyframe","parameters":{"handlerKey":"comic.shot_keyframe"}}},
                  {"id":"shot-videos","data":{"nodeDefType":"video_model","title":"Shot video","parameters":{"handlerKey":"comic.shot_video"}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """;
        String edges = """
                [
                  {"source":"start","target":"script"},
                  {"source":"script","target":"review"},
                  {"source":"review","target":"shot-audio"},
                  {"source":"review","target":"shot-keyframes"},
                  {"source":"shot-keyframes","target":"shot-videos"},
                  {"source":"shot-videos","target":"output"}
                ]
                """;
        String billing = fallbackBillingPolicy(Map.of(
                "script", 1,
                "shot-audio", 1,
                "shot-keyframes", 1,
                "shot-videos", 1
        ));
        jdbcTemplate.update(
                "UPDATE tool_workflow_versions SET nodes_json = ?, edges_json = ?, billing_policy_json = ? WHERE id = ?",
                nodes, edges, billing, firstVersionId
        );
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = 1");
        jdbcTemplate.update("DELETE FROM credit_accounts WHERE user_id = 1");
        jdbcTemplate.update("""
                INSERT INTO credit_accounts(
                  user_id, balance, membership_balance, gift_balance, frozen,
                  total_granted, total_consumed, status
                ) VALUES (1, 100, 100, 0, 0, 100, 0, 'ACTIVE')
                """);

        WorkflowRunCreated created = service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("""
                        {"operationHandlerKeys":["comic.shot_tts","comic.shot_keyframe","comic.shot_video"],
                         "comicShotId":7}
                        """),
                "workflow-operation-shot-7",
                "COMIC_PROJECT",
                null
        ));

        assertThat(jdbcTemplate.queryForList(
                "SELECT node_id, status FROM workflow_run_steps WHERE run_id = ? ORDER BY sequence_no",
                created.runId()
        )).containsExactly(
                java.util.Map.of("node_id", "shot-audio", "status", "QUEUED"),
                java.util.Map.of("node_id", "shot-keyframes", "status", "PENDING"),
                java.util.Map.of("node_id", "shot-videos", "status", "PENDING")
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts a JOIN workflow_run_steps s ON s.id = a.step_id WHERE s.run_id = ?",
                Integer.class,
                created.runId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT params_json FROM ai_tasks WHERE id = (SELECT task_id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'shot-audio')",
                String.class,
                created.runId()
        )).contains("comic.shot_tts");
    }

    @Test
    void comicAgentLaunchRunsOnlyScriptAndStoryboardBootstrap() throws Exception {
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"script","data":{"nodeDefType":"llm_text","title":"Script","parameters":{"handlerKey":"comic.script"}}},
                  {"id":"storyboard","data":{"nodeDefType":"llm_text","title":"Storyboard","parameters":{"handlerKey":"comic.storyboard"}}},
                  {"id":"review","data":{"nodeDefType":"user_confirm","title":"Review","parameters":{}}},
                  {"id":"video","data":{"nodeDefType":"video_model","title":"Video","parameters":{"handlerKey":"comic.shot_video"}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """;
        String edges = """
                [
                  {"source":"start","target":"script"},
                  {"source":"script","target":"storyboard"},
                  {"source":"storyboard","target":"review"},
                  {"source":"review","target":"video"},
                  {"source":"video","target":"output"}
                ]
                """;
        String billing = fallbackBillingPolicy(Map.of(
                "script", 1,
                "storyboard", 1,
                "video", 1
        ));
        jdbcTemplate.update(
                "UPDATE tool_workflow_versions SET nodes_json = ?, edges_json = ?, billing_policy_json = ? WHERE id = ?",
                nodes, edges, billing, firstVersionId
        );
        jdbcTemplate.update(
                "UPDATE ai_tools SET tool_code = 'ai_comic_drama_agent' WHERE id = ?",
                toolId
        );
        try {
            CreateWorkflowRunCommand command = new CreateWorkflowRunCommand(
                    1L,
                    "ai_comic_drama_agent",
                    objectMapper.readTree("{\"prompt\":\"ancient city adventure\"}"),
                    "workflow-comic-agent-bootstrap",
                    "AGENT_CHAT",
                    null
            );
            WorkflowRunCreated created = service.create(command);
            WorkflowRunCreated repeated = service.create(command);

            assertThat(repeated.runId()).isEqualTo(created.runId());
            assertThat(repeated.rootTaskId()).isEqualTo(created.rootTaskId());
            assertThat(jdbcTemplate.queryForList(
                    "SELECT node_id, status FROM workflow_run_steps WHERE run_id = ? ORDER BY sequence_no",
                    created.runId()
            )).containsExactly(
                    java.util.Map.of("node_id", "script", "status", "QUEUED"),
                    java.util.Map.of("node_id", "storyboard", "status", "PENDING")
            );
            JsonNode persistedInput = readJsonDocument(jdbcTemplate.queryForObject(
                    "SELECT input_json FROM workflow_runs WHERE id = ?",
                    String.class,
                    created.runId()
            ));
            assertThat(persistedInput.path("operationHandlerKeys").toString())
                    .contains("comic.script", "comic.storyboard")
                    .doesNotContain("comic.shot_video");
            assertThat(persistedInput.path("__workflowRequestIdentity").path("inputFingerprint").asText())
                    .hasSize(64);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT params_json FROM ai_tasks WHERE id = ("
                            + "SELECT task_id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'script')",
                    String.class,
                    created.runId()
            )).doesNotContain("__workflowRequestIdentity");

            ObjectNode legacyInput = ((ObjectNode) persistedInput).deepCopy();
            legacyInput.remove("__workflowRequestIdentity");
            jdbcTemplate.update(
                    "UPDATE workflow_runs SET input_json = ? WHERE id = ?",
                    objectMapper.writeValueAsString(legacyInput),
                    created.runId()
            );
            WorkflowRunCreated legacyRepeated = service.create(command);
            assertThat(legacyRepeated.runId()).isEqualTo(created.runId());
            assertThatThrownBy(() -> service.create(new CreateWorkflowRunCommand(
                    1L,
                    "ai_comic_drama_agent",
                    objectMapper.readTree("{\"prompt\":\"another city adventure\"}"),
                    "workflow-comic-agent-bootstrap",
                    "AGENT_CHAT",
                    null
            ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        } finally {
            jdbcTemplate.update(
                    "UPDATE ai_tools SET tool_code = ? WHERE id = ?",
                    TOOL_CODE,
                    toolId
            );
        }
    }

    @Test
    void runInputMatchesPersistedNormalizedRootTaskParams() throws Exception {
        WorkflowRunCreated created = service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{\"aspect_ratio\":\"16:9\"}"),
                "workflow-app-normalized-input",
                "AGENTS_PAGE",
                null
        ));

        String rootParamsJson = jdbcTemplate.queryForObject(
                "SELECT params_json FROM ai_tasks WHERE id = ?",
                String.class,
                created.rootTaskId()
        );
        String runInputJson = jdbcTemplate.queryForObject(
                "SELECT input_json FROM workflow_runs WHERE id = ?",
                String.class,
                created.runId()
        );
        assertThat(readJsonDocument(rootParamsJson).path("aspectRatio").asText()).isEqualTo("16:9");
        assertThat(readJsonDocument(runInputJson)).isEqualTo(readJsonDocument(rootParamsJson));
    }

    @Test
    void awaitingPreviewUsesRunPinnedDslAfterNewVersionIsPublished() throws Exception {
        configureFirstVersion(AWAITING_NODES, AWAITING_EDGES);
        WorkflowRunCreated created = service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{}"),
                "workflow-app-pinned-preview",
                "AGENTS_PAGE",
                null
        ));
        publishReplacementVersion();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                created.runId()
        )).isEqualTo("AWAITING_USER");
        var preview = executionService.buildWorkflowPreview(created.rootTaskId());
        assertThat(preview.path("fieldKey").asText()).isEqualTo("pinnedReview");
        assertThat(preview.path("stageLabel").asText()).isEqualTo("Pinned review");
    }

    @Test
    void oldRunAdvancesUsingPinnedDslAfterNewVersionIsPublished() throws Exception {
        configureFirstVersion(AWAITING_NODES, AWAITING_EDGES);
        WorkflowRunCreated created = service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{}"),
                "workflow-app-pinned-advance",
                "AGENTS_PAGE",
                null
        ));
        publishReplacementVersion();

        executionService.submitUserFeedback(created.rootTaskId(), 1L, java.util.Map.of());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                created.runId()
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_run_steps WHERE run_id = ? AND node_id = 'review'",
                String.class,
                created.runId()
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT input_json FROM workflow_runs WHERE id = ?",
                String.class,
                created.runId()
        )).contains("pinnedReview");
    }

    @Test
    void legacyUserInputNormalizesNullFieldValueToEmptyText() throws Exception {
        configureFirstVersion(AWAITING_NODES, AWAITING_EDGES);
        WorkflowRunCreated created = service.create(new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{}"),
                "workflow-app-null-feedback",
                "AGENTS_PAGE",
                null
        ));
        java.util.Map<String, String> feedback = new java.util.HashMap<>();
        feedback.put("pinnedReview", null);

        executionService.submitUserFeedback(created.rootTaskId(), 1L, feedback);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                created.runId()
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT input_json FROM workflow_runs WHERE id = ?",
                String.class,
                created.runId()
        )).contains("\"pinnedReview\":\"\"");
    }

    @Test
    void historicalRunWithoutVersionIdUsesDeclaredVersionSnapshot() throws Exception {
        configureFirstVersion(AWAITING_NODES, AWAITING_EDGES);
        HistoricalRun historical = insertHistoricalAwaitingRun(1);
        publishReplacementVersion();

        var preview = executionService.buildWorkflowPreview(historical.rootTaskId());
        executionService.submitUserFeedback(historical.rootTaskId(), 1L, java.util.Map.of());

        org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(preview.path("fieldKey").asText()).isEqualTo("pinnedReview");
            softly.assertThat(preview.path("stageLabel").asText()).isEqualTo("Pinned review");
            softly.assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM workflow_runs WHERE id = ?",
                    String.class,
                    historical.runId()
            )).isEqualTo("SUCCESS");
            softly.assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM workflow_run_steps WHERE run_id = ? AND node_id = 'review'",
                    String.class,
                    historical.runId()
            )).isEqualTo("SUCCESS");
        });
    }

    @Test
    void historicalRunWithoutMatchingVersionSnapshotFailsExplicitly() {
        configureFirstVersion(AWAITING_NODES, AWAITING_EDGES);
        HistoricalRun historical = insertHistoricalAwaitingRun(99);

        assertThatThrownBy(() -> executionService.submitUserFeedback(
                historical.rootTaskId(),
                1L,
                java.util.Map.of()
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
    }

    @Test
    void staleFailureCannotOverwriteTerminalRun() {
        int suffix = 20;
        for (String terminalStatus : java.util.List.of("SUCCESS", "CANCELLED")) {
            HistoricalRun historical = insertHistoricalAwaitingRun(suffix++);
            Long stepId = jdbcTemplate.queryForObject(
                    "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'review'",
                    Long.class,
                    historical.runId()
            );
            jdbcTemplate.update(
                    "UPDATE workflow_run_steps SET status = 'FAILED', revision = 3 WHERE id = ?",
                    stepId
            );
            jdbcTemplate.update(
                    "UPDATE workflow_runs SET status = ?, revision = 4, error_message = NULL WHERE id = ?",
                    terminalStatus,
                    historical.runId()
            );

            executionService.onStepAttemptsExhausted(stepId, new WorkerFailedRequest(
                    "MODEL_CALL_FAILED",
                    "late failure",
                    "CALLBACK",
                    false,
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    null
            ));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM workflow_runs WHERE id = ?",
                    String.class,
                    historical.runId()
            )).isEqualTo(terminalStatus);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT revision FROM workflow_runs WHERE id = ?",
                    Long.class,
                    historical.runId()
            )).isEqualTo(4L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM ai_tasks WHERE id = ?",
                    String.class,
                    historical.rootTaskId()
            )).isEqualTo("AWAITING_USER");
        }
    }

    @Test
    void workflowFailureFinishesDelegatedCallForTerminalAgentRunExactlyOnce() {
        HistoricalRun historical = insertHistoricalAwaitingRun(30);
        AgentLink agent = attachDelegatedAgentCall(historical.rootTaskId(), 1L, TOOL_CODE);
        Long stepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'review'",
                Long.class,
                historical.runId()
        );
        jdbcTemplate.update(
                "UPDATE workflow_run_steps SET status = 'FAILED', revision = revision + 1 WHERE id = ?",
                stepId
        );
        WorkerFailedRequest failure = new WorkerFailedRequest(
                "MODEL_CALL_FAILED",
                "provider failed permanently",
                "CALLBACK",
                false,
                null,
                null,
                null,
                0,
                0,
                0,
                null
        );

        executionService.onStepAttemptsExhausted(stepId, failure);
        executionService.onStepAttemptsExhausted(stepId, failure);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                historical.runId()
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?",
                String.class,
                historical.rootTaskId()
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, error_code, error_message FROM agent_tool_calls WHERE id = ?",
                agent.toolCallId()
        )).containsEntry("status", "FAILED")
                .containsEntry("error_code", "WORKFLOW_FAILED")
                .containsEntry("error_message", "provider failed permanently");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'tool.finished'",
                Integer.class,
                agent.agentRunId()
        )).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestReturnsOneRootTaskAndOneRun() throws Exception {
        CreateWorkflowRunCommand command = new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"same request\"}"),
                "workflow-app-concurrent",
                "AGENTS_PAGE",
                null
        );
        CountDownLatch snapshotsEstablished = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkflowRunCreated> firstFuture = executor.submit(
                    () -> createAfterRepeatableReadSnapshot(command, snapshotsEstablished)
            );
            Future<WorkflowRunCreated> secondFuture = executor.submit(
                    () -> createAfterRepeatableReadSnapshot(command, snapshotsEstablished)
            );

            WorkflowRunCreated first = firstFuture.get(15, TimeUnit.SECONDS);
            WorkflowRunCreated second = secondFuture.get(15, TimeUnit.SECONDS);

            assertThat(second.rootTaskId()).isEqualTo(first.rootTaskId());
            assertThat(second.runId()).isEqualTo(first.runId());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_tasks WHERE user_id = 1 AND idempotency_key = 'workflow-app-concurrent'",
                    Integer.class
            )).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_runs WHERE user_id = 1 AND client_request_id = 'workflow-app-concurrent'",
                    Integer.class
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameClientRequestCannotBeReusedForAnotherTool() throws Exception {
        CreateWorkflowRunCommand first = new CreateWorkflowRunCommand(
                1L,
                TOOL_CODE,
                objectMapper.readTree("{}"),
                "workflow-app-cross-tool",
                "AGENTS_PAGE",
                null
        );
        service.create(first);
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, status,
                  estimated_credit_cost, execution_handler, execution_mode,
                  billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES ('workflow_application_other', 'Other Workflow', 1, 'test', 'ONLINE', 0,
                          'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 1, 0, 0)
                """);

        CreateWorkflowRunCommand conflicting = new CreateWorkflowRunCommand(
                1L,
                "workflow_application_other",
                objectMapper.readTree("{}"),
                "workflow-app-cross-tool",
                "AGENTS_PAGE",
                null
        );
        assertThatThrownBy(() -> service.create(conflicting))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    private long insertVersion(int version, long sourceDraftRevision) {
        return insertVersion(version, sourceDraftRevision, NODES, EDGES);
    }

    private String fallbackBillingPolicy(Map<String, Integer> nodeCosts) {
        ObjectNode root = objectMapper.createObjectNode().put("mode", "WORKFLOW_STEP");
        ObjectNode nodePolicies = root.putObject("nodePolicies");
        nodeCosts.forEach((nodeId, credits) -> {
            ObjectNode nodePolicy = nodePolicies.putObject(nodeId);
            nodePolicy.put("maxCreditCost", credits);
            nodePolicy.put("estimatedProviderCostCny", 0);
            nodePolicy.put("maxProviderCostCny", 0);
            nodePolicy.put("fallbackChargeCredits", credits);
            nodePolicy.put("pricingSource", "TOOL_FALLBACK");
            nodePolicy.putObject("staticParams");
            nodePolicy.putNull("modelPricingSnapshot");
            ObjectNode pricingPolicy = nodePolicy.putObject("pricingPolicy");
            pricingPolicy.put("markupRatio", 1.0);
            pricingPolicy.put("minCredits", 0);
            pricingPolicy.put("imageEstimateInputTokens", 8000);
            pricingPolicy.put("imageEstimateOutputTokens", 8000);
            pricingPolicy.put("tokenEstimateInputTokens", 1000);
            pricingPolicy.put("tokenEstimateOutputTokens", 1000);
            pricingPolicy.putArray("rules");
        });
        return root.toString();
    }

    private PublishedWorkflow insertExecutableWorkflow(String workflowName) {
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, groups_json, config_json,
                  version, status, draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, NULL, '{}', 1, 'PUBLISHED', 1, 1,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, workflowName, NODES, EDGES);
        long insertedWorkflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ? AND workflow_name = ?",
                Long.class,
                toolId,
                workflowName
        );
        jdbcTemplate.update("""
                INSERT INTO tool_workflow_versions(
                  workflow_id, version, nodes_json, edges_json, groups_json, config_json,
                  canonical_dsl_json, dsl_version, node_registry_version, dsl_hash,
                  input_schema_snapshot_json, dependency_manifest_json, billing_policy_json,
                  risk_policy_json, source_draft_revision, published_at, published_by, created_at
                ) VALUES (?, 1, ?, ?, NULL, '{}', '{}', '1', 'p0', ?, ?, '{}',
                          '{"mode":"WORKFLOW_STEP","nodePolicies":{}}', '{}', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, insertedWorkflowId, NODES, EDGES, "hash-" + insertedWorkflowId + "-1", INPUT_SCHEMA);
        long insertedVersionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ? AND version = 1",
                Long.class,
                insertedWorkflowId
        );
        jdbcTemplate.update(
                "UPDATE tool_workflows SET published_version_id = ? WHERE id = ?",
                insertedVersionId,
                insertedWorkflowId
        );
        return new PublishedWorkflow(insertedWorkflowId, insertedVersionId);
    }

    private long insertVersion(int version,
                               long sourceDraftRevision,
                               String nodes,
                               String edges) {
        jdbcTemplate.update("""
                INSERT INTO tool_workflow_versions(
                  workflow_id, version, nodes_json, edges_json, groups_json, config_json,
                  canonical_dsl_json, dsl_version, node_registry_version, dsl_hash,
                  input_schema_snapshot_json, dependency_manifest_json, billing_policy_json,
                  risk_policy_json, source_draft_revision, published_at, published_by, created_at
                ) VALUES (?, ?, ?, ?, NULL, '{}', '{}', '1', 'p0', ?, ?, '{}',
                          '{"mode":"WORKFLOW_STEP","nodePolicies":{}}', '{}', ?, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, workflowId, version, nodes, edges, "hash-" + workflowId + "-" + version,
                INPUT_SCHEMA, sourceDraftRevision);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ? AND version = ?",
                Long.class,
                workflowId,
                version
        );
    }

    private void configureFirstVersion(String nodes, String edges) {
        jdbcTemplate.update(
                "UPDATE tool_workflow_versions SET nodes_json = ?, edges_json = ? WHERE id = ?",
                nodes,
                edges,
                firstVersionId
        );
        jdbcTemplate.update(
                "UPDATE tool_workflows SET nodes_json = ?, edges_json = ? WHERE id = ?",
                nodes,
                edges,
                workflowId
        );
    }

    private void publishReplacementVersion() {
        long replacementVersionId = insertVersion(2, 2L, REPLACEMENT_NODES, AWAITING_EDGES);
        jdbcTemplate.update("""
                UPDATE tool_workflows
                SET nodes_json = ?, edges_json = ?, published_version_id = ?, version = 2, draft_revision = 2
                WHERE id = ?
                """, REPLACEMENT_NODES, AWAITING_EDGES, replacementVersionId, workflowId);
    }

    private HistoricalRun insertHistoricalAwaitingRun(int workflowVersion) {
        String requestId = "workflow-app-historical-" + workflowVersion;
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(
                  task_no, user_id, tool_id, status, progress, progress_message,
                  params_json, idempotency_key, estimated_credit_cost, queued_at, started_at
                ) VALUES (?, 1, ?, 'AWAITING_USER', 35, 'Waiting for input', '{}', ?, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "HISTORICAL-WF-" + workflowVersion, toolId, requestId);
        long rootTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE user_id = 1 AND idempotency_key = ?",
                Long.class,
                requestId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, workflow_version, workflow_version_id,
                  root_task_id, launch_source, client_request_id, status, revision,
                  cancellation_generation, input_json, context_json, current_node_id,
                  billing_status, started_at, created_at, updated_at
                ) VALUES (1, ?, ?, ?, NULL, ?, 'LEGACY_TASK', ?, 'AWAITING_USER', 0,
                          0, '{}', '{}', 'review', 'CLEAR', CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, workflowId, workflowVersion, rootTaskId, requestId);
        long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE root_task_id = ?",
                Long.class,
                rootTaskId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(
                  run_id, node_id, sequence_no, node_def_type, status, revision,
                  attempt, attempt_count, max_attempts, finished_at
                ) VALUES (?, 'start', 1, 'START', 'SUCCESS', 0, 0, 0, 2, CURRENT_TIMESTAMP),
                         (?, 'review', 2, 'USER_INPUT', 'PENDING', 0, 0, 0, 2, NULL),
                         (?, 'output', 3, 'VIDEO_OUTPUT', 'PENDING', 0, 0, 0, 2, NULL)
                """, runId, runId, runId);
        return new HistoricalRun(rootTaskId, runId);
    }

    private AgentLink attachDelegatedAgentCall(Long rootTaskId, Long userId, String toolCode) {
        String key = "workflow-agent-link-" + rootTaskId;
        jdbcTemplate.update(
                "INSERT INTO agent_sessions(user_id, title, status) VALUES (?, ?, 'ACTIVE')",
                userId,
                key
        );
        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_sessions WHERE user_id = ? AND title = ?",
                Long.class,
                userId,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO agent_runs(
                  session_id, user_id, status, client_request_id, started_at, finished_at, created_at, updated_at
                ) VALUES (?, ?, 'SUCCESS', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, sessionId, userId, key);
        Long agentRunId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_runs WHERE user_id = ? AND client_request_id = ?",
                Long.class,
                userId,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO agent_tool_calls(
                  run_id, user_id, tool_code, task_id, status, arguments_json, started_at, created_at
                ) VALUES (?, ?, ?, ?, 'DELEGATED', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, agentRunId, userId, toolCode, rootTaskId);
        Long toolCallId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_tool_calls WHERE run_id = ? AND task_id = ?",
                Long.class,
                agentRunId,
                rootTaskId
        );
        return new AgentLink(agentRunId, toolCallId);
    }

    private WorkflowRunCreated createAfterRepeatableReadSnapshot(CreateWorkflowRunCommand command,
                                                                  CountDownLatch snapshotsEstablished) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                    Integer.class,
                    command.userId(),
                    command.clientRequestId()
            )).isZero();
            snapshotsEstablished.countDown();
            await(snapshotsEstablished);
            return service.create(command);
        });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent workflow creation");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrent workflow creation", exception);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readJsonDocument(String json) throws Exception {
        var parsed = objectMapper.readTree(json);
        return parsed.isTextual() ? objectMapper.readTree(parsed.asText()) : parsed;
    }

    private record HistoricalRun(long rootTaskId, long runId) {
    }

    private record AgentLink(long agentRunId, long toolCallId) {
    }

    private record PublishedWorkflow(long workflowId, long versionId) {
    }
}
