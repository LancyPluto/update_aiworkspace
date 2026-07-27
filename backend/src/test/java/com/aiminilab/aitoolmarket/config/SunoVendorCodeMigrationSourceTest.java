package com.aiminilab.aitoolmarket.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SunoVendorCodeMigrationSourceTest {

    @Test
    void migrationMergesRoutingPoolsBeforeCanonicalizingVendorCodesWithoutChangingModelProviders() throws Exception {
        String migration = Files.readString(Path.of("../sql/127_normalize_suno_vendor_codes.sql"));

        assertThat(migration).contains(
                "FROM model_account_routing_pools legacy",
                "JOIN model_account_routing_pools canonical",
                "canonical.pool_key = legacy.pool_key",
                "WHERE legacy.vendor_code = 'suno_music'",
                "tmp_suno_pool_redirects_127",
                "DECLARE EXIT HANDLER FOR SQLEXCEPTION",
                "ROLLBACK;",
                "RESIGNAL;",
                "legacy vendor_code remains",
                "duplicate suno routing pool key"
        );
        assertThat(migration).doesNotContain(
                "tmp_suno_pool_survivors_127",
                "pool_key VARCHAR",
                "COLLATE utf8mb4_unicode_ci PRIMARY KEY"
        );

        int accountRebind = migration.indexOf("UPDATE model_vendor_accounts account");
        int modelRebind = migration.indexOf("UPDATE agent_model_configs model");
        int duplicateDelete = migration.indexOf("DELETE pool");
        int poolCanonicalization = migration.indexOf(
                "UPDATE model_account_routing_pools\n  SET vendor_code = 'suno'"
        );
        int accountCanonicalization = migration.indexOf(
                "UPDATE model_vendor_accounts\n  SET vendor_code = 'suno'"
        );

        assertThat(accountRebind).isGreaterThanOrEqualTo(0);
        assertThat(modelRebind).isGreaterThan(accountRebind);
        assertThat(duplicateDelete).isGreaterThan(modelRebind);
        assertThat(poolCanonicalization).isGreaterThan(duplicateDelete);
        assertThat(accountCanonicalization).isGreaterThan(poolCanonicalization);

        assertThat(migration)
                .contains("SET model.routing_pool_id = redirect.target_pool_id")
                .doesNotContain(
                        "SET model.provider",
                        "provider = 'suno'",
                        "provider = 'suno_music'"
                );
    }
}
