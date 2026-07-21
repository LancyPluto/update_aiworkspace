package com.aiminilab.aitoolmarket.task.routing;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.aiminilab.aitoolmarket.task.routing.entity.TaskModelRouteAttempt;
import com.aiminilab.aitoolmarket.task.routing.mapper.AccountModelRouteStateMapper;
import com.aiminilab.aitoolmarket.task.routing.mapper.TaskModelRouteAttemptMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:model_routing_mapper_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.task.scheduling.enabled=false",
        "app.model-routing.enabled=false"
})
@Transactional
class ModelRoutingMapperIntegrationTest {
    @Autowired
    private AccountModelRouteStateMapper stateMapper;
    @Autowired
    private TaskModelRouteAttemptMapper attemptMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private AgentModelConfigMapper modelConfigMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reservesSwitchesAndReleasesWithCasGuards() {
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(task_no, user_id, tool_id, model_config_id, status, params_json, claim_token)
                VALUES ('routing-mapper-task', 1, 1, 90001, 'QUEUED', '{}', 'claim-1')
                """);
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE task_no = 'routing-mapper-task'", Long.class);

        assertThat(stateMapper.insertIfAbsent(80001L, 90001L)).isEqualTo(1);
        stateMapper.insertIfAbsent(80002L, 90001L);
        AccountModelRouteState state = stateMapper.findByModelConfigIdsForUpdate(List.of(90001L)).get(0);
        assertThat(state.getVendorAccountId()).isEqualTo(80002L);
        assertThat(stateMapper.reserve(state.getId(), 0)).isEqualTo(1);
        assertThat(stateMapper.reserve(state.getId(), 0)).isZero();

        TaskModelRouteAttempt first = attempt(taskId, 1, 90001L, 80001L);
        assertThat(attemptMapper.insertAttempt(first)).isEqualTo(1);
        assertThat(taskMapper.assignInitialRoute(
                taskId, 90001L, 80001L, first.getId(), "{\"route\":1}"))
                .isEqualTo(1);
        assertThat(attemptMapper.markProviderAccepted(first.getId(), "claim-1", "provider-first"))
                .isEqualTo(1);
        TaskModelRouteAttempt accepted = attemptMapper.selectById(first.getId());
        assertThat(accepted.getDeliveryState()).isEqualTo("ACCEPTED");
        assertThat(accepted.getProviderRequestId()).isEqualTo("provider-first");

        jdbcTemplate.update("UPDATE ai_tasks SET status = 'PROCESSING' WHERE id = ?", taskId);
        TaskModelRouteAttempt second = attempt(taskId, 2, 90002L, 80002L);
        assertThat(attemptMapper.insertAttempt(second)).isEqualTo(1);
        assertThat(taskMapper.switchRouteGuarded(
                taskId, "claim-1", first.getId(), 90002L, 80002L,
                second.getId(), "{\"route\":2}"))
                .isEqualTo(1);

        AiTask switched = taskMapper.selectByIdForUpdate(taskId);
        assertThat(switched.getModelConfigId()).isEqualTo(90001L);
        assertThat(switched.getSelectedModelConfigId()).isEqualTo(90002L);
        assertThat(switched.getSelectedVendorAccountId()).isEqualTo(80002L);
        assertThat(switched.getCurrentRouteAttemptId()).isEqualTo(second.getId());

        assertThat(attemptMapper.closeSuccess(second.getId(), "SUCCESS", "provider-second", true)).isEqualTo(1);
        assertThat(attemptMapper.closeSuccess(second.getId(), "SUCCESS", "provider-second", true)).isZero();
        TaskModelRouteAttempt completed = attemptMapper.selectById(second.getId());
        assertThat(completed.getDeliveryState()).isEqualTo("ACCEPTED");
        assertThat(completed.getProviderRequestId()).isEqualTo("provider-second");
        assertThat(completed.getProviderCharged()).isTrue();
        assertThat(stateMapper.releaseSuccess(state.getId())).isEqualTo(1);
        assertThat(stateMapper.selectById(state.getId()).getInFlightCount()).isZero();

        jdbcTemplate.update("""
                UPDATE account_model_route_state
                SET circuit_status = 'OPEN', consecutive_failures = 2,
                    cooldown_until = DATEADD('MINUTE', 5, CURRENT_TIMESTAMP)
                WHERE id = ?
                """, state.getId());
        assertThat(stateMapper.recoverByVendorAccountId(80002L)).isEqualTo(1);
        AccountModelRouteState recovered = stateMapper.selectById(state.getId());
        assertThat(recovered.getCircuitStatus()).isEqualTo("CLOSED");
        assertThat(recovered.getConsecutiveFailures()).isZero();
        assertThat(recovered.getCooldownUntil()).isNull();
        assertThat(stateMapper.releaseFailure(state.getId(), "CLOSED", null, 1)).isEqualTo(1);
        assertThat(stateMapper.selectById(state.getId()).getConsecutiveFailures()).isEqualTo(1);
        assertThat(stateMapper.releaseFailure(state.getId(), "CLOSED", null, 0)).isEqualTo(1);
        assertThat(stateMapper.selectById(state.getId()).getConsecutiveFailures()).isZero();

        stateMapper.insertIfAbsent(80002L, 90001L);
        assertThat(stateMapper.selectById(state.getId()).getVendorAccountId()).isEqualTo(80002L);
    }

    @Test
    void routingCandidatesIncludeModelsWithCachedProbeFailure() {
        jdbcTemplate.update("""
                INSERT INTO model_vendor_accounts(
                    vendor_code, account_name, enabled, load_balance_enabled
                ) VALUES ('openai', 'cached-probe-failure', 1, 1)
                """);
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM model_vendor_accounts WHERE account_name = 'cached-probe-failure'",
                Long.class);
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                    vendor_account_id, display_name, config_code, provider, model_name,
                    enabled, last_test_success
                ) VALUES (?, 'cached failure model', 'cached_failure_model',
                          'openai_images_gateway', 'gpt-image-2', 1, 0)
                """, accountId);

        List<AgentModelConfig> candidates = modelConfigMapper.findRoutingCandidates(
                "openai", "openai_images_gateway", "gpt-image-2");

        assertThat(candidates)
                .extracting(AgentModelConfig::getConfigCode)
                .contains("cached_failure_model");
    }

    private static TaskModelRouteAttempt attempt(Long taskId,
                                                 int attemptNo,
                                                 Long modelConfigId,
                                                 Long accountId) {
        TaskModelRouteAttempt attempt = new TaskModelRouteAttempt();
        attempt.setTaskId(taskId);
        attempt.setAttemptNo(attemptNo);
        attempt.setModelConfigId(modelConfigId);
        attempt.setVendorAccountId(accountId);
        attempt.setStatus("ACTIVE");
        attempt.setClaimToken("claim-1");
        return attempt;
    }
}
