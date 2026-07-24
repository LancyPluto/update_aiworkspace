package com.aiminilab.aitoolmarket.common.error;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorContractMigrationSourceTest {

    @Test
    void migrationDoesNotPromoteRawLegacyDiagnosticsToDeveloperMessages() throws Exception {
        String migration = Files.readString(Path.of("../sql/118_error_contract_phase1.sql"));

        assertThat(migration).contains("ADD COLUMN developer_message TEXT NULL");
        assertThat(migration).doesNotContain("UPDATE ");
    }
}
