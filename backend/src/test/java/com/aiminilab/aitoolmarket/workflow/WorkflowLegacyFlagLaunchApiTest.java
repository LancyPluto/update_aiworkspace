package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRecoveryScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_legacy_flag_launch_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "workflow.runtime.enabled=true",
        "workflow.runtime.execution-enabled=true",
        "workflow.runtime.real-billing-enabled=true",
        "spring.task.scheduling.enabled=false"
})
class WorkflowLegacyFlagLaunchApiTest {

    private static final String TOOL_CODE = "legacy_flags_launch_workflow";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private WorkflowRuntimeGate runtimeGate;

    @MockBean
    private TokenDenylistService tokenDenylistService;

    @MockBean
    private WorkflowRecoveryScheduler workflowRecoveryScheduler;

    private Long userId;
    private Long workflowId;

    @BeforeEach
    void setUp() {
        runtimeGate.markReconciliationHealthyAfterFullScan(runtimeGate.reconciliationFailureGeneration());
        jdbcTemplate.update("DELETE FROM users WHERE username = 'legacy_flags_launch_user'");
        jdbcTemplate.update("DELETE FROM ai_tools WHERE tool_code = ?", TOOL_CODE);
        jdbcTemplate.update(
                "INSERT INTO users(username, password_hash, user_type, status, is_deleted) VALUES ('legacy_flags_launch_user', 'x', 'USER', 'ACTIVE', 0)"
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = 'legacy_flags_launch_user'",
                Long.class
        );
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, description, status, estimated_credit_cost,
                  execution_handler, execution_mode, billing_mode,
                  agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, 'Legacy Flags Launch Workflow', 'test', 'ONLINE', 0,
                          'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 0, 0, 0)
                """, TOOL_CODE);
        Long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?",
                Long.class,
                TOOL_CODE
        );
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """;
        String edges = "[{\"id\":\"edge-1\",\"source\":\"start\",\"target\":\"output\"}]";
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, groups_json, config_json,
                  version, status, draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, 'default', ?, ?, NULL, '{}', 1, 'PUBLISHED', 1, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, nodes, edges);
        workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ? AND workflow_name = 'default'",
                Long.class,
                toolId
        );
        jdbcTemplate.update("""
                INSERT INTO tool_workflow_versions(
                  workflow_id, version, nodes_json, edges_json, groups_json, config_json,
                  canonical_dsl_json, dsl_version, node_registry_version, dsl_hash,
                  input_schema_snapshot_json, dependency_manifest_json, billing_policy_json,
                  risk_policy_json, source_draft_revision, published_at, published_by, created_at
                ) VALUES (?, 1, ?, ?, NULL, '{}', '{}', '1', 'p0', ?,
                          '{"type":"object","properties":{},"required":[]}', '{}',
                          '{"mode":"WORKFLOW_STEP","nodePolicies":{}}', '{}', 1,
                          CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, workflowId, nodes, edges, "legacy-flags-launch-" + workflowId);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ? AND version = 1",
                Long.class,
                workflowId
        );
        jdbcTemplate.update(
                "UPDATE tool_workflows SET published_version_id = ? WHERE id = ?",
                versionId,
                workflowId
        );
    }

    @Test
    void startsOnlinePublishedWorkflowWhenLegacyFlagsAreOff() throws Exception {
        String token = jwtTokenProvider.createToken(new AuthUser(userId, "legacy_flags_launch_user", "USER"));

        mockMvc.perform(post("/api/v1/agents/tools/{toolCode}/runs", TOOL_CODE)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"legacy-flags-api-start","input":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").isNumber())
                .andExpect(jsonPath("$.data.runId").isNumber());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ? AND workflow_id = ?",
                Integer.class,
                userId,
                workflowId
        )).isOne();
    }
}
