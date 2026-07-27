package com.aiminilab.aitoolmarket.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiGatewayVendorMigrationSourceTest {

    @Test
    void migrationRehomesGatewayAccountsAndPoolsWithoutChangingProviders() throws Exception {
        String migration = Files.readString(Path.of("../sql/129_normalize_openai_gateway_vendors.sql"));

        assertThat(migration).contains(
                "DECLARE EXIT HANDLER FOR SQLEXCEPTION",
                "ROLLBACK;",
                "RESIGNAL;",
                "REGEXP '^https?://api[.]openai[.]com([:/?#]|$)'",
                "REGEXP '^https?://([a-z0-9-]+[.])*ofox[.]ai([:/?#]|$)'",
                "unknown account host; classify it manually",
                "legacy pool model has no matching anchor account",
                "VALUES ('ofox', 'oFox', 'ofox', 70, 1)",
                "LEFT JOIN model_account_routing_pools destination",
                "destination.pool_key = source.pool_key",
                "tmp_gateway_pool_redirects_129",
                "SET account.routing_pool_id = redirect.target_pool_id",
                "SET model.routing_pool_id = redirect.target_pool_id",
                "legacy account routing pool reference remains",
                "legacy model routing pool reference remains",
                "model account or pool reference is inconsistent",
                "UPDATE model_vendors\n"
                        + "  SET enabled = 0,\n"
                        + "      updated_at = CURRENT_TIMESTAMP\n"
                        + "  WHERE vendor_code = 'openai_gateway'"
        );

        assertThat(migration.split(Pattern.quote(
                "INSERT INTO tmp_gateway_pool_account_targets_129(account_id, source_pool_id, target_kind)"
        ), -1)).hasSize(3);
        assertThat(migration).doesNotContain("UNION ALL");

        assertThat(migration).contains(
                "CREATE TEMPORARY TABLE tmp_gateway_account_targets_129 (\n"
                        + "    account_id BIGINT PRIMARY KEY,\n"
                        + "    target_kind TINYINT NOT NULL,\n"
                        + "    source_pool_id BIGINT NULL",
                "CREATE TEMPORARY TABLE tmp_gateway_pool_redirects_129 (\n"
                        + "    source_pool_id BIGINT NOT NULL,\n"
                        + "    target_kind TINYINT NOT NULL,\n"
                        + "    target_pool_id BIGINT NOT NULL"
        ).doesNotContain(
                "target_vendor VARCHAR",
                "CREATE TEMPORARY TABLE tmp_gateway_pool_keys_129",
                "SET model.provider",
                "provider = 'openai'",
                "provider = 'ofox'"
        );

        int unknownOwnershipGuard = migration.indexOf("unknown account host; classify it manually");
        int vendorUpsert = migration.indexOf("INSERT INTO model_vendors");
        int accountPoolRebind = migration.indexOf("UPDATE model_vendor_accounts account\n"
                + "  JOIN tmp_gateway_pool_account_targets_129");
        int modelPoolRebind = migration.indexOf("UPDATE agent_model_configs model\n"
                + "  JOIN tmp_gateway_pool_account_targets_129");
        int legacyPoolDelete = migration.indexOf("DELETE pool");
        int legacyVendorDisable = migration.lastIndexOf("UPDATE model_vendors");

        assertThat(unknownOwnershipGuard).isGreaterThanOrEqualTo(0);
        assertThat(vendorUpsert).isGreaterThan(unknownOwnershipGuard);
        assertThat(accountPoolRebind).isGreaterThan(vendorUpsert);
        assertThat(modelPoolRebind).isGreaterThan(accountPoolRebind);
        assertThat(legacyPoolDelete).isGreaterThan(modelPoolRebind);
        assertThat(legacyVendorDisable).isGreaterThan(legacyPoolDelete);
    }
}
