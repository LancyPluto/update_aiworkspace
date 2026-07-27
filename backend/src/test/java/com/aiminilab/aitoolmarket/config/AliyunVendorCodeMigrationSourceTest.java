package com.aiminilab.aitoolmarket.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AliyunVendorCodeMigrationSourceTest {

    @Test
    void migrationMergesRoutingPoolsBeforeCanonicalizingVendorCodes() throws Exception {
        String migration = Files.readString(Path.of("../sql/124_normalize_aliyun_vendor_codes.sql"));

        assertThat(migration).contains(
                "'bailian_happyhorse', 'dashscope', 'aliyun_bailian'",
                "MIN(CASE WHEN vendor_code = 'qwen' THEN id END)",
                "tmp_aliyun_pool_redirects_124"
        );

        int accountRebind = migration.indexOf("UPDATE model_vendor_accounts account");
        int modelRebind = migration.indexOf("UPDATE agent_model_configs model");
        int duplicateDelete = migration.indexOf("DELETE pool");
        int poolCanonicalization = migration.indexOf("UPDATE model_account_routing_pools\n  SET vendor_code = 'qwen'");
        int accountCanonicalization = migration.indexOf("UPDATE model_vendor_accounts\n  SET vendor_code = 'qwen'");

        assertThat(accountRebind).isGreaterThanOrEqualTo(0);
        assertThat(modelRebind).isGreaterThan(accountRebind);
        assertThat(duplicateDelete).isGreaterThan(modelRebind);
        assertThat(poolCanonicalization).isGreaterThan(duplicateDelete);
        assertThat(accountCanonicalization).isGreaterThan(poolCanonicalization);
    }
}
