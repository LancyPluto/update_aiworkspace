package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_run_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class WorkflowRunApiTest {

    private static final String ELIGIBLE_TOOL = "wf_api_eligible";
    private static final String HIDDEN_TOOL = "wf_api_hidden";
    private static final String DISABLED_TOOL = "wf_api_disabled";
    private static final String DIRECT_TOOL = "wf_api_direct";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TokenDenylistService tokenDenylistService;

    @MockBean
    private WorkflowRunApplicationService runApplicationService;

    private Long ownerId;
    private Long otherUserId;
    private String ownerToken;
    private String otherToken;
    private Long eligibleToolId;
    private Long eligibleWorkflowId;
    private Long eligibleVersionId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("workflow_api_owner");
        otherUserId = insertUser("workflow_api_other");
        ownerToken = token(ownerId, "workflow_api_owner");
        otherToken = token(otherUserId, "workflow_api_other");

        Long categoryId = insertCategory();
        eligibleToolId = insertTool(categoryId, ELIGIBLE_TOOL, "ONLINE", "WORKFLOW", true);
        Long hiddenToolId = insertTool(categoryId, HIDDEN_TOOL, "ONLINE", "WORKFLOW", false);
        Long disabledToolId = insertTool(categoryId, DISABLED_TOOL, "ONLINE", "WORKFLOW", true);
        insertTool(categoryId, DIRECT_TOOL, "ONLINE", "DIRECT", true);

        PublishedWorkflow eligible = insertPublishedWorkflow(eligibleToolId, true);
        eligibleWorkflowId = eligible.workflowId();
        eligibleVersionId = eligible.versionId();
        insertPublishedWorkflow(hiddenToolId, true);
        insertPublishedWorkflow(disabledToolId, false);
    }

    @Test
    void agentsApiRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/agents/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void listsOnlyPublishedAgentSurfaceWorkflowTools() throws Exception {
        mockMvc.perform(get("/api/v1/agents/tools")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].toolCode").value(ELIGIBLE_TOOL))
                .andExpect(jsonPath("$.data.items[0].minimumRequiredCredits").value(3))
                .andExpect(jsonPath("$.data.items[0].variableCreditPricing").value(true));

        mockMvc.perform(get("/api/v1/agents/tools/{toolCode}", ELIGIBLE_TOOL)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value(ELIGIBLE_TOOL))
                .andExpect(jsonPath("$.data.workflowVersion").value(1))
                .andExpect(jsonPath("$.data.inputSchema.properties.prompt.type").value("string"));

        mockMvc.perform(get("/api/v1/agents/tools/{toolCode}", HIDDEN_TOOL)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOOL_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/agents/tools/{toolCode}", DISABLED_TOOL)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/agents/tools/{toolCode}", DIRECT_TOOL)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void paginatesAndFiltersWorkflowToolsInStableDatabaseOrder() throws Exception {
        Long mediaCategoryId = insertCategory("media-lab", "Media Lab");
        Long alphaId = insertTool(mediaCategoryId, "wf_media_alpha", "ONLINE", "WORKFLOW", true);
        Long betaId = insertTool(mediaCategoryId, "wf_media_beta", "ONLINE", "WORKFLOW", true);
        Long gammaId = insertTool(mediaCategoryId, "wf_media_gamma", "ONLINE", "WORKFLOW", true);
        insertPublishedWorkflow(alphaId, true);
        insertPublishedWorkflow(betaId, true);
        insertPublishedWorkflow(gammaId, true);

        mockMvc.perform(get("/api/v1/agents/tools")
                        .header("Authorization", bearer(ownerToken))
                        .param("page", "2")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[0].toolCode").value("wf_media_alpha"))
                .andExpect(jsonPath("$.data.items[1].toolCode").value(ELIGIBLE_TOOL))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        mockMvc.perform(get("/api/v1/agents/tools")
                        .header("Authorization", bearer(ownerToken))
                        .param("keyword", "BETA")
                        .param("category", "media-lab"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].toolCode").value("wf_media_beta"))
                .andExpect(jsonPath("$.data.items[0].categoryName").value("Media Lab"));
    }

    @Test
    void usesDefaultWorkflowAsCanonicalWhenASecondPublishedWorkflowExists() throws Exception {
        jdbcTemplate.update(
                "UPDATE tool_workflow_versions SET config_json = ?, input_schema_snapshot_json = ? WHERE id = ?",
                "{\"adapterKey\":\"default-adapter\"}",
                "{\"properties\":{\"defaultOnly\":{\"type\":\"string\"}}}",
                eligibleVersionId
        );
        insertPublishedWorkflow(
                eligibleToolId,
                true,
                "alternate",
                "{\"adapterKey\":\"alternate-adapter\"}",
                "{\"properties\":{\"alternateOnly\":{\"type\":\"string\"}}}"
        );

        mockMvc.perform(get("/api/v1/agents/tools")
                        .header("Authorization", bearer(ownerToken))
                        .param("keyword", ELIGIBLE_TOOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].adapterKey").value("default-adapter"));

        mockMvc.perform(get("/api/v1/agents/tools/{toolCode}", ELIGIBLE_TOOL)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adapterKey").value("default-adapter"))
                .andExpect(jsonPath("$.data.inputSchema.properties.defaultOnly.type").value("string"))
                .andExpect(jsonPath("$.data.inputSchema.properties.alternateOnly").doesNotExist());

        when(runApplicationService.create(any())).thenReturn(
                new WorkflowRunCreated(7101L, 8101L, eligibleVersionId, "RUNNING")
        );
        mockMvc.perform(post("/api/v1/agents/tools/{toolCode}/runs", ELIGIBLE_TOOL)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"canonical-launch","input":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void createsRunThroughSharedApplicationService() throws Exception {
        when(runApplicationService.create(any())).thenReturn(
                new WorkflowRunCreated(7001L, 8001L, eligibleVersionId, "RUNNING")
        );

        mockMvc.perform(post("/api/v1/agents/tools/{toolCode}/runs", ELIGIBLE_TOOL)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId": "workflow-api-request-1",
                                  "input": {"prompt": "write a launch plan"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(7001))
                .andExpect(jsonPath("$.data.runId").value(8001))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        ArgumentCaptor<CreateWorkflowRunCommand> captor = ArgumentCaptor.forClass(CreateWorkflowRunCommand.class);
        verify(runApplicationService).create(captor.capture());
        CreateWorkflowRunCommand command = captor.getValue();
        assertThat(command.userId()).isEqualTo(ownerId);
        assertThat(command.toolCode()).isEqualTo(ELIGIBLE_TOOL);
        assertThat(command.clientRequestId()).isEqualTo("workflow-api-request-1");
        assertThat(command.launchSource()).isEqualTo("AGENTS_PAGE");
        assertThat(command.input().path("prompt").asText()).isEqualTo("write a launch plan");

    }

    @Test
    void mapsIdempotencyConflictTo409() throws Exception {
        when(runApplicationService.create(any())).thenThrow(
                new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "clientRequestId already used")
        );

        mockMvc.perform(post("/api/v1/agents/tools/{toolCode}/runs", ELIGIBLE_TOOL)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"duplicate-key","input":{}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void mapsRuntimeAdmissionFailureToStable503Contract() throws Exception {
        when(runApplicationService.create(any())).thenThrow(
                new BusinessException(
                        ErrorCode.WORKFLOW_RUNTIME_BLOCKED,
                        "Workflow runtime is not accepting new runs",
                        java.util.Map.of("reason", "runtime_disabled")
                )
        );

        mockMvc.perform(post("/api/v1/agents/tools/{toolCode}/runs", ELIGIBLE_TOOL)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"runtime-disabled","input":{}}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WORKFLOW_RUNTIME_BLOCKED"))
                .andExpect(jsonPath("$.data.reason").value("runtime_disabled"));
    }

    @Test
    void returnsRunStepsCostsAndEnforcesOwnership() throws Exception {
        RunFixture owned = insertRun(ownerId, "owned-workflow-task");
        RunFixture foreign = insertRun(otherUserId, "foreign-workflow-task");

        mockMvc.perform(get("/api/v1/agents/runs/{rootTaskId}", owned.rootTaskId())
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(owned.rootTaskId()))
                .andExpect(jsonPath("$.data.runId").value(owned.runId()))
                .andExpect(jsonPath("$.data.taskNo").value("owned-workflow-task"))
                .andExpect(jsonPath("$.data.toolCode").value(ELIGIBLE_TOOL))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.steps[0].stepCode").value("draft"))
                .andExpect(jsonPath("$.data.steps[0].charges[0].reservedCredits").value(5))
                .andExpect(jsonPath("$.data.cost.reservedCredits").value(5))
                .andExpect(jsonPath("$.data.artifacts").isArray());

        mockMvc.perform(get("/api/v1/agents/runs/{rootTaskId}", foreign.rootTaskId())
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/agents/runs/{rootTaskId}", 99999999L)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void returnsStructuredRootChildAndStepOutputArtifacts() throws Exception {
        RunFixture owned = insertRun(ownerId, "artifact-workflow-task");
        Long firstStepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? ORDER BY sequence_no LIMIT 1",
                Long.class,
                owned.runId()
        );
        long childTaskId = owned.rootTaskId() + 100000;
        jdbcTemplate.update(
                "UPDATE workflow_run_steps SET task_id = ?, output_json = ? WHERE id = ?",
                childTaskId,
                "{\"ignored\":true}",
                firstStepId
        );
        jdbcTemplate.update(
                """
                INSERT INTO workflow_run_steps(
                  run_id, node_id, sequence_no, node_def_type, status, revision,
                  attempt, attempt_count, max_attempts, input_json, output_json
                ) VALUES (?, 'summarize', 2, 'TRANSFORM', 'SUCCESS', 1, 0, 0, 1, '{}', ?)
                """,
                owned.runId(),
                "{\"summary\":\"fallback result\"}"
        );

        insertResource(owned.rootTaskId(), "IMAGE", "https://cdn.example.com/root.png", 1);
        insertResource(owned.rootTaskId(), "VIDEO", "{\"finalVideoUrl\":\"https://cdn.example.com/final.mp4\",\"duration\":12}", 2);
        insertResource(owned.rootTaskId(), "JSON", "{\"score\":98,\"ok\":true}", 3);
        insertResource(owned.rootTaskId(), "TEXT", "https://example.com/plain-text", 4);
        insertResource(childTaskId, "AUDIO", "{\"audioUrl\":\"https://cdn.example.com/voice.mp3\"}", 1);

        mockMvc.perform(get("/api/v1/agents/runs/{rootTaskId}", owned.rootTaskId())
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifacts[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.data.artifacts[0].url").value("https://cdn.example.com/root.png"))
                .andExpect(jsonPath("$.data.artifacts[0].downloadUrl").value("https://cdn.example.com/root.png"))
                .andExpect(jsonPath("$.data.artifacts[1].type").value("VIDEO"))
                .andExpect(jsonPath("$.data.artifacts[1].url").value("https://cdn.example.com/final.mp4"))
                .andExpect(jsonPath("$.data.artifacts[1].content.duration").value(12))
                .andExpect(jsonPath("$.data.artifacts[2].type").value("JSON"))
                .andExpect(jsonPath("$.data.artifacts[2].content.score").value(98))
                .andExpect(jsonPath("$.data.artifacts[3].type").value("TEXT"))
                .andExpect(jsonPath("$.data.artifacts[3].url").doesNotExist())
                .andExpect(jsonPath("$.data.artifacts[3].content").value("https://example.com/plain-text"))
                .andExpect(jsonPath("$.data.steps[0].artifacts[0].stepId").value(firstStepId))
                .andExpect(jsonPath("$.data.steps[0].artifacts[0].type").value("AUDIO"))
                .andExpect(jsonPath("$.data.steps[0].artifacts[0].url").value("https://cdn.example.com/voice.mp3"))
                .andExpect(jsonPath("$.data.steps[1].artifacts[0].type").value("JSON"))
                .andExpect(jsonPath("$.data.steps[1].artifacts[0].content.summary").value("fallback result"));
    }

    @Test
    void aggregatesCapturedReleasedAndMultipleAttemptCharges() throws Exception {
        RunFixture owned = insertRun(ownerId, "multi-attempt-cost-task");
        Long stepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ?",
                Long.class,
                owned.runId()
        );
        jdbcTemplate.update(
                "UPDATE workflow_step_charges SET status = 'RELEASED', reserved_credits = 5, charged_credits = 0 WHERE run_id = ?",
                owned.runId()
        );
        jdbcTemplate.update(
                """
                INSERT INTO workflow_step_charges(
                  run_id, step_id, attempt_id, user_id, status,
                  reserved_credits, charged_credits, idempotency_key
                ) VALUES (?, ?, ?, ?, 'CAPTURED', 7, 6, ?)
                """,
                owned.runId(),
                stepId,
                stepId + 20000,
                ownerId,
                "captured-charge-" + owned.rootTaskId()
        );

        mockMvc.perform(get("/api/v1/agents/runs/{rootTaskId}", owned.rootTaskId())
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cost.totalCredits").value(6))
                .andExpect(jsonPath("$.data.cost.capturedCredits").value(6))
                .andExpect(jsonPath("$.data.cost.reservedCredits").value(0))
                .andExpect(jsonPath("$.data.cost.releasedCredits").value(5))
                .andExpect(jsonPath("$.data.cost.charges.length()").value(2))
                .andExpect(jsonPath("$.data.steps[0].charges.length()").value(2));
    }

    @Test
    void legacyAgentToolPickerKeepsItsEightFieldContract() throws Exception {
        String response = mockMvc.perform(get("/api/v1/agent/tools")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode tools = objectMapper.readTree(response).path("data");
        JsonNode eligible = null;
        for (JsonNode tool : tools) {
            if (ELIGIBLE_TOOL.equals(tool.path("toolCode").asText())) {
                eligible = tool;
                break;
            }
        }
        assertThat(eligible).isNotNull();
        Set<String> fieldNames = new HashSet<>();
        eligible.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyInAnyOrder(
                "toolCode",
                "toolName",
                "description",
                "outputModality",
                "coverUrl",
                "estimatedCreditCost",
                "autoCallEnabled",
                "disabled"
        );
    }

    private Long insertUser(String username) {
        jdbcTemplate.update(
                "INSERT INTO users(username, password_hash, user_type, status, is_deleted) VALUES (?, 'x', 'USER', 'ACTIVE', 0)",
                username
        );
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private Long insertCategory() {
        return insertCategory("workflow-api", "Workflow API");
    }

    private Long insertCategory(String categoryCode, String categoryName) {
        jdbcTemplate.update(
                "INSERT INTO tool_categories(category_code, category_name, sort_order, status) VALUES (?, ?, 1, 'ACTIVE')",
                categoryCode,
                categoryName
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tool_categories WHERE category_code = ?",
                Long.class,
                categoryCode
        );
    }

    private void insertResource(Long taskId, String resourceType, String contentText, int sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO ai_result_resources(task_id, user_id, resource_type, content_text, sort_order)
                VALUES (?, ?, ?, ?, ?)
                """,
                taskId,
                ownerId,
                resourceType,
                contentText,
                sortOrder
        );
    }

    private Long insertTool(Long categoryId,
                            String toolCode,
                            String status,
                            String executionMode,
                            boolean agentSurfaceEnabled) {
        jdbcTemplate.update(
                """
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, cover_url, status,
                  estimated_credit_cost, execution_mode, billing_mode,
                  agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, ?, ?, ?, ?, ?, 7, ?, 'WORKFLOW_STEP', ?, 3, 0)
                """,
                toolCode,
                "Workflow API Tool " + toolCode,
                categoryId,
                "Workflow API integration test tool",
                "/covers/workflow-api.png",
                status,
                executionMode,
                agentSurfaceEnabled ? 1 : 0
        );
        Long toolId = jdbcTemplate.queryForObject("SELECT id FROM ai_tools WHERE tool_code = ?", Long.class, toolCode);
        if (agentSurfaceEnabled) {
            jdbcTemplate.update(
                    """
                    INSERT INTO agent_tool_descriptor_extension(
                      tool_id, tool_code, agent_enabled, agent_recommendable,
                      agent_auto_callable, confirmation_policy, risk_level, health_status
                    ) VALUES (?, ?, 1, 1, 0, 'auto', 'low', 'UNKNOWN')
                    """,
                    toolId,
                    toolCode
            );
        }
        return toolId;
    }

    private PublishedWorkflow insertPublishedWorkflow(Long toolId, boolean executionEnabled) {
        return insertPublishedWorkflow(
                toolId,
                executionEnabled,
                "default",
                "{}",
                "{\"properties\":{\"prompt\":{\"type\":\"string\",\"title\":\"Prompt\"}},\"required\":[\"prompt\"]}"
        );
    }

    private PublishedWorkflow insertPublishedWorkflow(Long toolId,
                                                       boolean executionEnabled,
                                                       String workflowName,
                                                       String configJson,
                                                       String inputSchemaJson) {
        jdbcTemplate.update(
                """
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, config_json,
                  version, status, draft_revision, execution_enabled
                ) VALUES (?, ?, '[]', '[]', '{}', 1, 'PUBLISHED', 1, ?)
                """,
                toolId,
                workflowName,
                executionEnabled ? 1 : 0
        );
        Long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ? AND workflow_name = ?",
                Long.class,
                toolId,
                workflowName
        );
        jdbcTemplate.update(
                """
                INSERT INTO tool_workflow_versions(
                  workflow_id, version, nodes_json, edges_json, config_json,
                  input_schema_snapshot_json, published_at, published_by
                ) VALUES (?, 1, '[]', '[]', ?, ?, CURRENT_TIMESTAMP, ?)
                """,
                workflowId,
                configJson,
                inputSchemaJson,
                ownerId
        );
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ?",
                Long.class,
                workflowId
        );
        jdbcTemplate.update(
                "UPDATE tool_workflows SET published_version_id = ? WHERE id = ?",
                versionId,
                workflowId
        );
        return new PublishedWorkflow(workflowId, versionId);
    }

    private RunFixture insertRun(Long userId, String taskNo) {
        jdbcTemplate.update(
                """
                INSERT INTO ai_tasks(
                  task_no, user_id, tool_id, status, progress, progress_message,
                  params_json, estimated_credit_cost, started_at
                ) VALUES (?, ?, ?, 'PROCESSING', 35, 'Drafting', '{}', 7, CURRENT_TIMESTAMP)
                """,
                taskNo,
                userId,
                eligibleToolId
        );
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE task_no = ?",
                Long.class,
                taskNo
        );
        jdbcTemplate.update(
                """
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, workflow_version, workflow_version_id,
                  root_task_id, launch_source, client_request_id, status, revision,
                  cancellation_generation, input_json, context_json, current_node_id,
                  billing_status, started_at
                ) VALUES (?, ?, ?, 1, ?, ?, 'AGENTS_PAGE', ?, 'RUNNING', 2, 0,
                          '{}', '{}', 'draft', 'RESERVED', CURRENT_TIMESTAMP)
                """,
                userId,
                eligibleToolId,
                eligibleWorkflowId,
                eligibleVersionId,
                taskId,
                "request-" + taskNo
        );
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE root_task_id = ?",
                Long.class,
                taskId
        );
        jdbcTemplate.update(
                """
                INSERT INTO workflow_run_steps(
                  run_id, node_id, sequence_no, node_def_type, status, revision,
                  attempt, attempt_count, max_attempts, input_json, started_at
                ) VALUES (?, 'draft', 1, 'TOOL', 'RUNNING', 1, 1, 1, 2, '{}', CURRENT_TIMESTAMP)
                """,
                runId
        );
        Long stepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ?",
                Long.class,
                runId
        );
        jdbcTemplate.update(
                "UPDATE workflow_runs SET current_step_id = ? WHERE id = ?",
                stepId,
                runId
        );
        jdbcTemplate.update(
                """
                INSERT INTO workflow_step_charges(
                  run_id, step_id, attempt_id, user_id, status,
                  reserved_credits, charged_credits, idempotency_key
                ) VALUES (?, ?, ?, ?, 'RESERVED', 5, 0, ?)
                """,
                runId,
                stepId,
                stepId + 10000,
                userId,
                "charge-" + taskNo
        );
        return new RunFixture(taskId, runId);
    }

    private String token(Long userId, String username) {
        return jwtTokenProvider.createToken(new AuthUser(userId, username, "USER"));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record PublishedWorkflow(Long workflowId, Long versionId) {
    }

    private record RunFixture(Long rootTaskId, Long runId) {
    }
}
