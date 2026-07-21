package com.aiminilab.aitoolmarket.task.routing;

import com.aiminilab.aitoolmarket.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:model_route_failover_api_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.task.scheduling.enabled=false",
        "app.model-routing.enabled=false",
        "app.model-routing.max-failovers=2"
})
@AutoConfigureMockMvc
@Transactional
class ModelRouteFailoverApiIntegrationTest {

    private static final String CLAIM_TOKEN = "worker-route-contract-claim";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AppProperties appProperties;

    @Test
    void workerNotSentContractSwitchesTheRealBackendRoute() throws Exception {
        Fixture fixture = fixture(null);
        String body = failureBody(fixture.routeAttemptId(), "NOT_SENT", "ACCOUNT", "CONNECT",
                "CONNECTION_REFUSED");

        MvcResult result = performFailover(fixture.taskId(), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.switched").value(true))
                .andExpect(jsonPath("$.data.reason").value("switched"))
                .andExpect(jsonPath("$.data.executionContext.routeAttemptId").isNumber())
                .andExpect(jsonPath("$.data.executionContext.modelConfig.id").value(fixture.targetModelId()))
                .andExpect(jsonPath("$.data.executionContext.modelConfig.vendorAccountId")
                        .value(fixture.targetAccountId()))
                .andExpect(jsonPath("$.data.executionContext.modelConfig.baseUrl")
                        .value("http://target-route.invalid/v1"))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        long nextAttemptId = data.path("routeAttemptId").asLong();

        assertThat(longValue("SELECT model_config_id FROM ai_tasks WHERE id = ?", fixture.taskId()))
                .isEqualTo(fixture.sourceModelId());
        assertThat(longValue("SELECT selected_model_config_id FROM ai_tasks WHERE id = ?", fixture.taskId()))
                .isEqualTo(fixture.targetModelId());
        assertThat(longValue("SELECT selected_vendor_account_id FROM ai_tasks WHERE id = ?", fixture.taskId()))
                .isEqualTo(fixture.targetAccountId());
        assertThat(longValue("SELECT current_route_attempt_id FROM ai_tasks WHERE id = ?", fixture.taskId()))
                .isEqualTo(nextAttemptId);
        assertThat(stringValue("SELECT status FROM task_model_route_attempts WHERE id = ?",
                fixture.routeAttemptId())).isEqualTo("SWITCHED");
        assertThat(stringValue("SELECT delivery_state FROM task_model_route_attempts WHERE id = ?",
                fixture.routeAttemptId())).isEqualTo("NOT_SENT");
        assertThat(longValue("SELECT vendor_account_id FROM task_model_route_attempts WHERE id = ?",
                nextAttemptId)).isEqualTo(fixture.targetAccountId());
        assertThat(stringValue("SELECT status FROM task_model_route_attempts WHERE id = ?", nextAttemptId))
                .isEqualTo("ACTIVE");
        assertThat(intValue("SELECT in_flight_count FROM account_model_route_state WHERE model_config_id = ?",
                fixture.sourceModelId())).isZero();
        assertThat(intValue("SELECT in_flight_count FROM account_model_route_state WHERE model_config_id = ?",
                fixture.targetModelId())).isEqualTo(1);
    }

    @Test
    void workerReadTimeoutContractCannotSwitchTheRealBackendRoute() throws Exception {
        Fixture fixture = fixture(null);
        String body = failureBody(fixture.routeAttemptId(), "UNKNOWN", "NONE", "SUBMIT", "READ_TIMEOUT");

        performFailover(fixture.taskId(), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.switched").value(false))
                .andExpect(jsonPath("$.data.reason").value("unsafe_delivery_state"))
                .andExpect(jsonPath("$.data.routeAttemptId").value(fixture.routeAttemptId()))
                .andExpect(jsonPath("$.data.executionContext").isEmpty());

        assertRouteUnchanged(fixture);
    }

    @Test
    void persistedCheckpointRejectsAnOtherwiseSafeWorkerFailover() throws Exception {
        Fixture fixture = fixture("""
                {"kind":"VIDEO_SUBMISSION","status":"SUBMITTED","requestId":"provider-accepted"}
                """);
        String body = failureBody(fixture.routeAttemptId(), "NOT_SENT", "ACCOUNT", "CONNECT",
                "CONNECTION_REFUSED");

        performFailover(fixture.taskId(), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.switched").value(false))
                .andExpect(jsonPath("$.data.reason").value("unsafe_delivery_state"))
                .andExpect(jsonPath("$.data.routeAttemptId").value(fixture.routeAttemptId()))
                .andExpect(jsonPath("$.data.executionContext").isEmpty());

        assertRouteUnchanged(fixture);
    }

    private org.springframework.test.web.servlet.ResultActions performFailover(Long taskId, String body)
            throws Exception {
        String path = "/api/internal/v1/tasks/%d/route-failover".formatted(taskId);
        appProperties.getModelRouting().setEnabled(true);
        try {
            return mockMvc.perform(signed(post(path), "POST", path, body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        } finally {
            appProperties.getModelRouting().setEnabled(false);
        }
    }

    private Fixture fixture(String providerCheckpointJson) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String vendorCode = "route-contract-" + suffix;
        Long sourceAccountId = insertAccount(vendorCode, "source-" + suffix,
                "http://source-route.invalid/v1", "source-key");
        Long targetAccountId = insertAccount(vendorCode, "target-" + suffix,
                "http://target-route.invalid/v1", "target-key");
        Long sourceModelId = insertModel(sourceAccountId, "source-model-" + suffix,
                "route_contract_source_" + suffix);
        Long targetModelId = insertModel(targetAccountId, "target-model-" + suffix,
                "route_contract_target_" + suffix);
        Long toolId = insertTool(sourceModelId, suffix);
        Long taskId = insertTask(toolId, sourceModelId, sourceAccountId, suffix, providerCheckpointJson);
        Long routeAttemptId = insertAttempt(taskId, sourceModelId, sourceAccountId);
        jdbcTemplate.update("UPDATE ai_tasks SET current_route_attempt_id = ? WHERE id = ?",
                routeAttemptId, taskId);
        insertRouteState(sourceAccountId, sourceModelId, 1);
        insertRouteState(targetAccountId, targetModelId, 0);
        return new Fixture(taskId, routeAttemptId, sourceAccountId, targetAccountId,
                sourceModelId, targetModelId);
    }

    private Long insertAccount(String vendorCode, String accountName, String baseUrl, String apiKey) {
        jdbcTemplate.update("""
                INSERT INTO model_vendor_accounts(
                    vendor_code, account_name, base_url, api_key, enabled, is_deleted,
                    load_balance_enabled, load_balance_weight
                ) VALUES (?, ?, ?, ?, 1, 0, 1, 100)
                """, vendorCode, accountName, baseUrl, apiKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM model_vendor_accounts WHERE vendor_code = ? AND account_name = ?",
                Long.class, vendorCode, accountName);
    }

    private Long insertModel(Long accountId, String displayName, String configCode) {
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                    vendor_account_id, display_name, config_code, provider, model_name,
                    billing_unit, unit_price, capabilities, enabled, agent_enabled, is_deleted
                ) VALUES (?, ?, ?, 'openai_compatible', 'fake-chat-model',
                          'TOKEN_PER_M', 0, 'TEXT_GENERATION', 1, 1, 0)
                """, accountId, displayName, configCode);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM agent_model_configs WHERE config_code = ?", Long.class, configCode);
    }

    private Long insertTool(Long modelConfigId, String suffix) {
        String toolCode = "route_contract_tool_" + suffix;
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                    tool_code, tool_name, tool_type, input_modality, output_modality,
                    status, model_config_id, execution_handler, is_deleted
                ) VALUES (?, 'Route contract tool', 'TEXT_GENERATION', 'TEXT', 'TEXT',
                          'ACTIVE', ?, 'TEXT_GENERATION', 0)
                """, toolCode, modelConfigId);
        return jdbcTemplate.queryForObject("SELECT id FROM ai_tools WHERE tool_code = ?", Long.class, toolCode);
    }

    private Long insertTask(Long toolId,
                            Long modelConfigId,
                            Long sourceAccountId,
                            String suffix,
                            String providerCheckpointJson) {
        String taskNo = "route-contract-task-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(
                    task_no, user_id, tool_id, model_config_id, selected_model_config_id,
                    selected_vendor_account_id, status, params_json, claim_token,
                    provider_checkpoint_json, provider_checkpoint_version
                ) VALUES (?, 1, ?, ?, ?, ?, 'PROCESSING', '{}', ?, ?, ?)
                """, taskNo, toolId, modelConfigId, modelConfigId, sourceAccountId, CLAIM_TOKEN,
                providerCheckpointJson, providerCheckpointJson == null ? 0 : 1);
        return jdbcTemplate.queryForObject("SELECT id FROM ai_tasks WHERE task_no = ?", Long.class, taskNo);
    }

    private Long insertAttempt(Long taskId, Long modelConfigId, Long accountId) {
        jdbcTemplate.update("""
                INSERT INTO task_model_route_attempts(
                    task_id, attempt_no, model_config_id, vendor_account_id, status, claim_token
                ) VALUES (?, 1, ?, ?, 'ACTIVE', ?)
                """, taskId, modelConfigId, accountId, CLAIM_TOKEN);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM task_model_route_attempts WHERE task_id = ? AND attempt_no = 1",
                Long.class, taskId);
    }

    private void insertRouteState(Long accountId, Long modelConfigId, int inFlightCount) {
        jdbcTemplate.update("""
                INSERT INTO account_model_route_state(
                    vendor_account_id, model_config_id, in_flight_count, circuit_status,
                    consecutive_failures, version
                ) VALUES (?, ?, ?, 'CLOSED', 0, 0)
                """, accountId, modelConfigId, inFlightCount);
    }

    private String failureBody(Long routeAttemptId,
                               String deliveryState,
                               String retryScope,
                               String failureStage,
                               String errorCode) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("claimToken", CLAIM_TOKEN)
                .put("routeAttemptId", routeAttemptId)
                .put("deliveryState", deliveryState)
                .put("retryScope", retryScope)
                .put("failureStage", failureStage)
                .put("errorCode", errorCode)
                .put("errorMessage", "worker route contract failure")
                .putNull("providerErrorCode")
                .putNull("providerRequestId")
                .put("providerCharged", false)
                .putNull("retryAfterSeconds"));
    }

    private void assertRouteUnchanged(Fixture fixture) {
        assertThat(longValue("SELECT selected_model_config_id FROM ai_tasks WHERE id = ?", fixture.taskId()))
                .isEqualTo(fixture.sourceModelId());
        assertThat(longValue("SELECT selected_vendor_account_id FROM ai_tasks WHERE id = ?", fixture.taskId()))
                .isEqualTo(fixture.sourceAccountId());
        assertThat(longValue("SELECT current_route_attempt_id FROM ai_tasks WHERE id = ?", fixture.taskId()))
                .isEqualTo(fixture.routeAttemptId());
        assertThat(intValue("SELECT COUNT(*) FROM task_model_route_attempts WHERE task_id = ?", fixture.taskId()))
                .isEqualTo(1);
        assertThat(stringValue("SELECT status FROM task_model_route_attempts WHERE id = ?",
                fixture.routeAttemptId())).isEqualTo("ACTIVE");
        assertThat(intValue("SELECT in_flight_count FROM account_model_route_state WHERE model_config_id = ?",
                fixture.sourceModelId())).isEqualTo(1);
        assertThat(intValue("SELECT in_flight_count FROM account_model_route_state WHERE model_config_id = ?",
                fixture.targetModelId())).isZero();
    }

    private long longValue(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private int intValue(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private String stringValue(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private record Fixture(
            Long taskId,
            Long routeAttemptId,
            Long sourceAccountId,
            Long targetAccountId,
            Long sourceModelId,
            Long targetModelId
    ) {
    }
}
