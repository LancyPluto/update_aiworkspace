package com.aiminilab.aitoolmarket.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SeedanceModelDisplayNameMigrationSourceTest {

    @Test
    void migrationRenamesExistingSeedance15RowsWithoutOverwritingTheirContract() throws Exception {
        String migration = Files.readString(Path.of("../sql/125_seed_seedance_15_model_contract.sql"));

        assertThat(migration)
                .contains("UPDATE agent_model_configs model")
                .contains("model.is_deleted = 0")
                .contains("model.config_code = 'volcengine-gateway-video'")
                .contains("model.model_name = 'doubao-seedance-1-5-pro-251215'")
                .contains("model.display_name = 'Seedance 1.5 Pro（火山方舟）'")
                .doesNotContain("INSERT INTO agent_model_configs")
                .doesNotContain("request_schema_json")
                .doesNotContain("request_mapping_json")
                .doesNotContain("response_mapping_json")
                .doesNotContain("contract_status")
                .doesNotContain("capabilities =")
                .doesNotContain("model.provider =");
    }
}
