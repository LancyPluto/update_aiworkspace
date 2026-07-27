package com.aiminilab.aitoolmarket.ppt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ppt_workspace_schema_contract_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class PptWorkspaceSchemaContractTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v2TablesExposePlatformOwnedProjectVersionJobAndExportState() {
        assertColumn("ppt_projects", "legacy_binding_id");
        assertColumn("ppt_projects", "current_deck_version_id");
        assertColumn("ppt_projects", "text_model_config_id");
        assertColumn("ppt_projects", "image_model_config_id");
        assertColumn("ppt_engine_bindings", "external_project_id");
        assertColumn("ppt_deck_versions", "content_spec_json");
        assertColumn("ppt_slides", "engine_page_id");
        assertColumn("ppt_slide_versions", "conversion_mode");
        assertColumn("ppt_jobs", "idempotency_key");
        assertColumn("ppt_jobs", "credit_state");
        assertColumn("ppt_jobs", "next_poll_at");
        assertColumn("ppt_exports", "storage_url");
        assertColumn("ppt_model_invocations", "ai_task_id");
    }

    @Test
    void v2TablesKeepLegacyBindingAndJobIdempotencyUnique() {
        assertUniqueConstraint("ppt_projects", "uk_ppt_project_legacy_binding");
        assertUniqueConstraint("ppt_engine_bindings", "uk_ppt_engine_binding_project");
        assertUniqueConstraint("ppt_jobs", "uk_ppt_job_user_idempotency");
        assertUniqueConstraint("ppt_exports", "uk_ppt_export_job_type");
        assertUniqueConstraint("ppt_model_invocations", "uk_ppt_model_invocation_job_key");
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

    private void assertUniqueConstraint(String table, String constraint) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE LOWER(table_name) = ?
                  AND LOWER(constraint_name) = ?
                  AND constraint_type = 'UNIQUE'
                """,
                Integer.class,
                table,
                constraint
        );
        assertThat(count)
                .as("unique constraint %s.%s", table, constraint)
                .isEqualTo(1);
    }
}
