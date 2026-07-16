package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:credit_reservation_idempotency_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class CreditReservationIdempotencyTest {

    private static final long USER_ID = 9001L;

    @Autowired
    private CreditService creditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM credit_accounts WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("""
                INSERT INTO credit_accounts(
                  user_id, balance, membership_balance, gift_balance, frozen,
                  total_granted, total_consumed, status
                ) VALUES (?, 100, 100, 0, 0, 100, 0, 'ACTIVE')
                """, USER_ID);
    }

    @Test
    void tryFreezeIsIdempotentAndRejectsConflictingReplay() {
        String key = "workflow:1:step:2:attempt:1:reserve";

        assertThat(creditService.tryFreeze(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, key
        )).isTrue();
        assertThat(creditService.tryFreeze(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, key
        )).isTrue();

        assertThat(accountValue("frozen")).isEqualTo(30);
        assertThat(logCount(key)).isEqualTo(1);
        assertThatThrownBy(() -> creditService.tryFreeze(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 31, key
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void insufficientFreezeLeavesNoIdempotencyCredential() {
        String key = "workflow:1:step:2:attempt:1:reserve";

        assertThat(creditService.tryFreeze(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 101, key
        )).isFalse();

        assertThat(accountValue("frozen")).isZero();
        assertThat(logCount(key)).isZero();
    }

    @Test
    void concurrentFreezeWithSameKeyReturnsNormallyAndFreezesOnce() throws Exception {
        String key = "workflow:1:step:2:attempt:1:reserve";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return creditService.tryFreeze(
                        USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, key
                );
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return creditService.tryFreeze(
                        USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, key
                );
            });
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(accountValue("frozen")).isEqualTo(30);
        assertThat(logCount(key)).isEqualTo(1);
    }

    @Test
    void captureReservedDeductsActualAndReleasesDifferenceExactlyOnce() {
        String reserveKey = "workflow:1:step:2:attempt:1:reserve";
        String captureKey = "workflow:1:step:2:attempt:1:capture";
        creditService.tryFreeze(USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, reserveKey);

        creditService.captureReserved(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, 18, captureKey
        );
        creditService.captureReserved(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, 18, captureKey
        );

        assertThat(accountValue("balance")).isEqualTo(82);
        assertThat(accountValue("frozen")).isZero();
        assertThat(accountValue("total_consumed")).isEqualTo(18);
        assertThat(logCount(captureKey)).isEqualTo(1);
        assertThatThrownBy(() -> creditService.captureReserved(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, 19, captureKey
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releaseReservedIsIdempotent() {
        String reserveKey = "workflow:1:step:2:attempt:1:reserve";
        String releaseKey = "workflow:1:step:2:attempt:1:release";
        creditService.tryFreeze(USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, reserveKey);

        creditService.releaseReserved(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, releaseKey
        );
        creditService.releaseReserved(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 30, releaseKey
        );

        assertThat(accountValue("balance")).isEqualTo(100);
        assertThat(accountValue("frozen")).isZero();
        assertThat(logCount(releaseKey)).isEqualTo(1);
    }

    @Test
    void captureReservedConsumesMembershipBeforeGiftWithoutNegativeBuckets() {
        jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = 15, membership_balance = 5, gift_balance = 10,
                    frozen = 0, total_consumed = 0
                WHERE user_id = ?
                """, USER_ID);
        String reserveKey = "workflow:1:step:2:attempt:1:reserve";
        creditService.tryFreeze(USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 15, reserveKey);

        creditService.captureReserved(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 15, 8,
                "workflow:1:step:2:attempt:1:capture"
        );

        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, membership_balance, gift_balance, frozen FROM credit_accounts WHERE user_id = ?",
                USER_ID
        )).containsEntry("balance", 7)
                .containsEntry("membership_balance", 0)
                .containsEntry("gift_balance", 7)
                .containsEntry("frozen", 0);
    }

    @Test
    void settleConsumesMembershipBeforeGift() {
        setBuckets(15, 5, 10);
        creditService.freeze(USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 8);

        creditService.settle(USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 8);

        assertBuckets(7, 0, 7, 0);
    }

    @Test
    void deductAvailableLeavesGiftUntouchedWhenMembershipIsSufficient() {
        setBuckets(15, 10, 5);

        assertThat(creditService.deductAvailable(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 8
        )).isEqualTo(8);

        assertBuckets(7, 2, 5, 0);
    }

    @Test
    void manualDeductConsumesMembershipBeforeGift() {
        setBuckets(15, 5, 10);

        creditService.manualDeduct(USER_ID, 8, "test", 1L);

        assertBuckets(7, 0, 7, 0);
    }

    @Test
    void captureReservedDoesNotMakeBucketsNegativeWhenBalanceContainsLegacyUnbucketedCredits() {
        jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = 100, membership_balance = 5, gift_balance = 5,
                    frozen = 0, total_consumed = 0
                WHERE user_id = ?
                """, USER_ID);
        creditService.tryFreeze(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 50,
                "workflow:1:step:2:attempt:legacy:reserve"
        );

        creditService.captureReserved(
                USER_ID, CreditSourceType.WORKFLOW_STEP, 2L, 50, 50,
                "workflow:1:step:2:attempt:legacy:capture"
        );

        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, membership_balance, gift_balance, frozen FROM credit_accounts WHERE user_id = ?",
                USER_ID
        )).containsEntry("balance", 50)
                .containsEntry("membership_balance", 0)
                .containsEntry("gift_balance", 0)
                .containsEntry("frozen", 0);
    }

    @Test
    void manualAddCreditsEnterGiftBucket() {
        creditService.manualAdd(USER_ID, 20, "test", 1L);

        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, membership_balance, gift_balance FROM credit_accounts WHERE user_id = ?",
                USER_ID
        )).containsEntry("balance", 120)
                .containsEntry("membership_balance", 100)
                .containsEntry("gift_balance", 20);
    }

    private int accountValue(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM credit_accounts WHERE user_id = ?",
                Integer.class,
                USER_ID
        );
    }

    private void setBuckets(int balance, int membership, int gift) {
        jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = ?, membership_balance = ?, gift_balance = ?,
                    frozen = 0, total_consumed = 0
                WHERE user_id = ?
                """, balance, membership, gift, USER_ID);
    }

    private void assertBuckets(int balance, int membership, int gift, int frozen) {
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, membership_balance, gift_balance, frozen FROM credit_accounts WHERE user_id = ?",
                USER_ID
        )).containsEntry("balance", balance)
                .containsEntry("membership_balance", membership)
                .containsEntry("gift_balance", gift)
                .containsEntry("frozen", frozen);
    }

    private int logCount(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE idempotency_key = ?",
                Integer.class,
                key
        );
    }
}
