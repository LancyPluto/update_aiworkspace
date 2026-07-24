package com.aiminilab.aitoolmarket.common.error;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorContractMigrationSourceTest {

    @Test
    void historicalMigrationRemainsImmutableAndCleanupRemovesRawLegacyDiagnostics() throws Exception {
        Path historicalPath = Path.of("../sql/118_error_contract_phase1.sql");
        byte[] historicalBytes = Files.readAllBytes(historicalPath);
        String cleanup = Files.readString(Path.of("../sql/120_error_contract_legacy_diagnostic_cleanup.sql"));

        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(historicalBytes)))
                .isEqualTo("246e619d0e69e53326746b9cfb552b8032d9930b3fae15dabd19541c90d7a18e");
        assertThat(cleanup.split("SET developer_message = NULL", -1)).hasSize(9);
        assertThat(cleanup)
                .contains("failure_trace_id IS NULL")
                .contains("BINARY developer_message = BINARY LEFT(error_message, 2000)");
        for (String table : new String[]{
                "ai_tasks",
                "ai_task_logs",
                "task_model_route_attempts",
                "agent_runs",
                "agent_tool_calls",
                "workflow_runs",
                "workflow_run_steps",
                "workflow_step_attempts"
        }) {
            assertThat(cleanup).contains("UPDATE " + table);
        }
    }
}
