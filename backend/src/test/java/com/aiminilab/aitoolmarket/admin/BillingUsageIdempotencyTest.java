package com.aiminilab.aitoolmarket.admin;

import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing_usage_idempotency_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class BillingUsageIdempotencyTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM billing_usage_logs WHERE source_type = 'WORKFLOW_STEP'");
    }

    @Test
    void recordUsageOnceCreatesOneRowAndRejectsConflictingReplay() {
        String key = "workflow:1:step:2:attempt:1:usage";

        Long first = billingService.recordUsageOnce(
                key, "WORKFLOW_STEP", 2L, 9001L, null,
                10, 5, 1, 18, new BigDecimal("0.15"), new BigDecimal("1.2")
        );
        Long duplicate = billingService.recordUsageOnce(
                key, "WORKFLOW_STEP", 2L, 9001L, null,
                10, 5, 1, 18, new BigDecimal("0.15"), new BigDecimal("1.2")
        );

        assertThat(duplicate).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                key
        )).isEqualTo(1);
        assertThatThrownBy(() -> billingService.recordUsageOnce(
                key, "WORKFLOW_STEP", 2L, 9001L, null,
                10, 5, 1, 19, new BigDecimal("0.15"), new BigDecimal("1.2")
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void perCharacterUsageDerivesVendorCostWhenProviderCostIsNotReported() {
        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setProvider("dashscope_qwen_tts");
        modelConfig.setModelName("qwen3-tts-flash");
        modelConfig.setBillingUnit("PER_CHARACTER");
        modelConfig.setUnitPrice(new BigDecimal("0.02"));

        Long usageId = billingService.recordUsageOnce(
                "workflow:1:step:tts:attempt:1:usage",
                "WORKFLOW_STEP",
                3L,
                9001L,
                modelConfig,
                0,
                0,
                12,
                36,
                null,
                new BigDecimal("1.50")
        );

        assertThat(jdbcTemplate.queryForMap(
                "SELECT billing_unit, billable_units, vendor_cost_amount, charged_credits "
                        + "FROM billing_usage_logs WHERE id = ?",
                usageId
        )).containsEntry("billing_unit", "PER_CHARACTER")
                .containsEntry("billable_units", 12)
                .containsEntry("vendor_cost_amount", new BigDecimal("0.240000"))
                .containsEntry("charged_credits", 36);
    }

    @Test
    void concurrentUsageWithSameKeyReturnsSameRow() throws Exception {
        String key = "workflow:1:step:2:attempt:1:usage";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Long> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return billingService.recordUsageOnce(
                        key, "WORKFLOW_STEP", 2L, 9001L, null,
                        10, 5, 1, 18, new BigDecimal("0.15"), new BigDecimal("1.2")
                );
            });
            Future<Long> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return billingService.recordUsageOnce(
                        key, "WORKFLOW_STEP", 2L, 9001L, null,
                        10, 5, 1, 18, new BigDecimal("0.15"), new BigDecimal("1.2")
                );
            });
            start.countDown();
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(first.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                key
        )).isEqualTo(1);
    }

    @Test
    void chargedProviderFailureWithoutKnownAmountStillLeavesEvidence() {
        String key = "workflow:1:step:2:attempt:1:usage";

        Long usageId = billingService.recordUsageOnce(
                key, "WORKFLOW_STEP", 2L, 9001L, null,
                0, 0, 0, 0, null, null,
                "FAILED", "UPSTREAM_TIMEOUT", "PROVIDER_CALL",
                null, "provider-request-unknown-cost", true
        );

        assertThat(usageId).isNotNull();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT outcome, provider_charged, provider_request_id, charged_credits "
                        + "FROM billing_usage_logs WHERE id = ?",
                usageId
        )).containsEntry("outcome", "FAILED")
                .containsEntry("provider_charged", 1)
                .containsEntry("provider_request_id", "provider-request-unknown-cost")
                .containsEntry("charged_credits", 0);
    }

    @Test
    void emptySuccessfulUsageWithoutProviderEvidenceIsSkipped() {
        String key = "workflow:1:step:2:attempt:empty:usage";

        Long usageId = billingService.recordUsageOnce(
                key, "WORKFLOW_STEP", 2L, 9001L, null,
                0, 0, 0, 0, null, null
        );

        assertThat(usageId).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                key
        )).isZero();
    }

    @Test
    void explicitProviderNotChargedOverridesSuccessAndReportedAmount() {
        String key = "workflow:1:step:2:attempt:not-charged:usage";

        Long usageId = billingService.recordUsageOnce(
                key, "WORKFLOW_STEP", 2L, 9001L, null,
                10, 5, 1, 18, new BigDecimal("9.99"), "CNY", new BigDecimal("1.2"),
                "SUCCESS", null, "PROVIDER_CALLBACK", null, "provider-request-not-charged", false
        );

        assertThat(jdbcTemplate.queryForMap(
                "SELECT vendor_cost_amount, provider_charged FROM billing_usage_logs WHERE id = ?",
                usageId
        )).containsEntry("vendor_cost_amount", new BigDecimal("0.000000"))
                .containsEntry("provider_charged", 0);
    }
}
