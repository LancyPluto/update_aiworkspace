package com.aiminilab.aitoolmarket.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataInitializerSourceTest {

    @Test
    void defaultImageToolSupportsOptionalSourceImageForImageToImage() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/aiminilab/aitoolmarket/config/DataInitializer.java"));
        int start = source.indexOf("private void seedDefaultTextToImageTool()");
        int end = source.indexOf("private void seedAgnesTextToVideoField", start);
        String defaultImageToolSource = source.substring(start, end);

        assertThat(defaultImageToolSource).contains("sourceImageUrl");
        assertThat(defaultImageToolSource).contains("seedToolField(\"gpt_image_text_to_image\", \"sourceImageUrl\"");
        assertThat(defaultImageToolSource).contains("input_modality = 'MULTIMODAL'");
        assertThat(defaultImageToolSource).contains("未上传图片走文生图，上传图片走图文生图");
    }

    @Test
    void legacyCreditBucketsAreCreatedBeforePermanentBucketMigration() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/aiminilab/aitoolmarket/config/DataInitializer.java"));
        int membershipColumn = source.indexOf("ensureColumn(\"credit_accounts\", \"membership_balance\"");
        int giftColumn = source.indexOf("ensureColumn(\"credit_accounts\", \"gift_balance\"");
        int permanentMigration = source.indexOf("SET permanent_balance = CASE");

        assertThat(membershipColumn).isGreaterThanOrEqualTo(0);
        assertThat(giftColumn).isGreaterThan(membershipColumn);
        assertThat(permanentMigration).isGreaterThan(giftColumn);
        assertThat(source).contains("WHEN membership_balance = 0 AND gift_balance = 0 THEN balance");
    }

    @Test
    void giftCardTableIsCreatedBeforeAddingIssuanceKey() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/aiminilab/aitoolmarket/config/DataInitializer.java"));
        int giftCardTable = source.indexOf("ensureTable(\"gift_cards\"");
        int issuanceKeyColumn = source.indexOf("ensureColumn(\"gift_cards\", \"issuance_key\"");

        assertThat(giftCardTable).isGreaterThanOrEqualTo(0);
        assertThat(issuanceKeyColumn).isGreaterThan(giftCardTable);
    }

    @Test
    void membershipMigrationOnlyGuardsActualMembershipOrders() throws Exception {
        String migration = Files.readString(Path.of("../sql/087_membership_and_recharge_idempotency.sql"));
        String source = Files.readString(Path.of("src/main/java/com/aiminilab/aitoolmarket/config/DataInitializer.java"));

        assertThat(migration).contains("SIGNAL SQLSTATE '45000'");
        assertThat(migration).contains("order_type = 'MEMBERSHIP'");
        assertThat(source).contains("order_type = 'MEMBERSHIP'");
        assertThat(migration).doesNotContain("WHERE package_id IS NOT NULL\n    AND status IN ('PAID', 'CREDITED')");
        assertThat(migration).doesNotContain("CREATE TEMPORARY TABLE membership_migration_guard");
    }
}
