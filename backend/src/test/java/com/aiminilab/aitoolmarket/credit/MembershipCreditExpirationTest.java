package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.impl.MembershipExpirationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:membership_credit_expiration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.production-mode=false"
})
class MembershipCreditExpirationTest {
    @Autowired
    private CreditMapper creditMapper;

    @Autowired
    private CreditService creditService;

    @Autowired
    private MembershipExpirationScheduler membershipExpirationScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void expirationClearsOnlyMembershipCredits() {
        long userId = 91_001L;
        CreditAccount account = creditMapper.getOrCreateAccount(userId);
        jdbcTemplate.update("UPDATE credit_accounts SET permanent_balance = 200, membership_balance = 1000, gift_balance = 50, balance = 1250 WHERE id = ?", account.getId());
        insertExpiredMembership(userId, 501L);

        creditService.account(userId);

        assertThat(bucket(userId, "permanent_balance")).isEqualTo(200);
        assertThat(bucket(userId, "membership_balance")).isZero();
        assertThat(bucket(userId, "gift_balance")).isEqualTo(50);
        assertThat(bucket(userId, "balance")).isEqualTo(250);
        assertThat(bucket(userId, "total_expired")).isEqualTo(1000);
    }

    @Test
    void frozenMembershipCreditsExpireWithoutReturningOnRelease() {
        long userId = 91_002L;
        CreditAccount account = creditMapper.getOrCreateAccount(userId);
        jdbcTemplate.update("UPDATE credit_accounts SET permanent_balance = 200, membership_balance = 1000, balance = 1200 WHERE id = ?", account.getId());
        insertActiveMembership(userId, 502L);
        creditService.freezeForTask(userId, 7001L, 100);
        jdbcTemplate.update("UPDATE user_memberships SET expires_at = ? WHERE user_id = ?", LocalDateTime.now().minusMinutes(1), userId);

        creditService.account(userId);

        assertThat(bucket(userId, "membership_balance")).isZero();
        assertThat(bucket(userId, "expired_membership_frozen")).isEqualTo(100);
        assertThat(bucket(userId, "balance")).isEqualTo(300);
        assertThat(bucket(userId, "frozen")).isEqualTo(100);

        creditService.releaseForTask(userId, 7001L, 100);

        assertThat(bucket(userId, "expired_membership_frozen")).isZero();
        assertThat(bucket(userId, "balance")).isEqualTo(200);
        assertThat(bucket(userId, "frozen")).isZero();
    }

    @Test
    void scheduledSweepExpiresMembershipWithoutUserTraffic() {
        long userId = 91_003L;
        CreditAccount account = creditMapper.getOrCreateAccount(userId);
        jdbcTemplate.update(
                "UPDATE credit_accounts SET membership_balance = 1000, balance = permanent_balance + 1000 WHERE id = ?",
                account.getId());
        insertExpiredMembership(userId, 503L);

        int processed = membershipExpirationScheduler.expireExpiredMemberships();

        assertThat(processed).isGreaterThanOrEqualTo(1);
        assertThat(bucket(userId, "membership_balance")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM user_memberships WHERE user_id = ?", String.class, userId))
                .isEqualTo("EXPIRED");
    }

    private void insertExpiredMembership(long userId, long orderId) {
        insertMembership(userId, orderId, LocalDateTime.now().minusDays(1));
    }

    private void insertActiveMembership(long userId, long orderId) {
        insertMembership(userId, orderId, LocalDateTime.now().plusDays(1));
    }

    private void insertMembership(long userId, long orderId, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO user_memberships(user_id, status, package_id, package_code, order_id, started_at, expires_at, created_at, updated_at)
                VALUES (?, 'ACTIVE', 1, 'ci_recharge_1000', ?, ?, ?, ?, ?)
                """, userId, orderId, now.minusDays(1), expiresAt, now, now);
    }

    private int bucket(long userId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM credit_accounts WHERE user_id = ?", Integer.class, userId);
    }
}
