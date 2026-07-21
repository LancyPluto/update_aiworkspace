package com.aiminilab.aitoolmarket.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_schema_contract_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class WorkflowSchemaContractTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void workflowTablesExposeVersionRevisionAttemptAndChargeColumns() {
        assertColumn("workflow_runs", "workflow_version_id");
        assertColumn("workflow_runs", "revision");
        assertColumn("workflow_runs", "provider_cost_reserved_cny");
        assertColumn("workflow_provider_cost_budget_days", "budget_date");
        assertColumn("workflow_run_steps", "revision");
        assertColumn("workflow_step_attempts", "claim_token");
        assertColumn("workflow_step_attempts", "cancellation_generation");
        assertColumn("workflow_step_charges", "status");
        assertColumn("billing_usage_logs", "provider_cost_currency");
        assertColumn("workflow_confirmations", "token_hash");
    }

    @Test
    void taskTableExposesProviderCheckpointCompareAndSetColumns() {
        assertColumn("ai_tasks", "provider_checkpoint_json");
        assertColumn("ai_tasks", "provider_checkpoint_version");
        Long checkpointCapacity = jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE LOWER(table_name) = 'ai_tasks'
                  AND LOWER(column_name) = 'provider_checkpoint_json'
                """,
                Long.class
        );
        assertThat(checkpointCapacity).isGreaterThanOrEqualTo(1_048_576L);
    }

    private void assertColumn(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE LOWER(table_name) = ? AND LOWER(column_name) = ?
                """,
                Integer.class,
                table,
                column
        );
        assertThat(count)
                .as("column %s.%s", table, column)
                .isEqualTo(1);
    }
}
