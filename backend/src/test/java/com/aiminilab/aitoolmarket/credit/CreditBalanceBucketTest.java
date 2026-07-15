package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:credit_balance_bucket_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.production-mode=false"
})
class CreditBalanceBucketTest {

  @Autowired
  private CreditService creditService;

  @Autowired
  private CreditMapper creditMapper;

  @Test
  void deductsPermanentBalanceBeforeGiftBalanceWhenNoMembershipExists() {
    long userId = 88_001L;
    CreditAccount account = creditMapper.getOrCreateAccount(userId);
    creditMapper.rechargeAdd(account.getId(), 100);
    creditMapper.giftRedeemAdd(account.getId(), 50);

    CreditAccount funded = creditMapper.findByUserId(userId).orElseThrow();
    assertThat(funded.getPermanentBalance()).isEqualTo(300);
    assertThat(funded.getMembershipBalance()).isZero();
    assertThat(funded.getGiftBalance()).isEqualTo(50);
    assertThat(funded.getBalance()).isEqualTo(350);

    creditService.freezeForTask(userId, 1L, 120);
    creditService.settleForTask(userId, 1L, 120);

    CreditAccount after = creditMapper.findByUserId(userId).orElseThrow();
    assertThat(after.getPermanentBalance()).isEqualTo(180);
    assertThat(after.getGiftBalance()).isEqualTo(50);
    assertThat(after.getBalance()).isEqualTo(230);

    creditService.freezeForTask(userId, 2L, 200);
    creditService.settleForTask(userId, 2L, 200);

    CreditAccount depleted = creditMapper.findByUserId(userId).orElseThrow();
    assertThat(depleted.getPermanentBalance()).isEqualTo(0);
    assertThat(depleted.getGiftBalance()).isEqualTo(30);
    assertThat(depleted.getBalance()).isEqualTo(30);
  }

  @Test
  void manualDeductUsesMembershipThenPermanentThenGiftBuckets() {
    long userId = 88_002L;
    CreditAccount account = creditMapper.getOrCreateAccount(userId);
    creditMapper.membershipRechargeAdd(account.getId(), 100);
    creditMapper.rechargeAdd(account.getId(), 100);
    creditMapper.giftRedeemAdd(account.getId(), 50);

    creditService.manualDeduct(userId, 350, "bucket consistency", 1L);

    CreditAccount after = creditMapper.findByUserId(userId).orElseThrow();
    assertThat(after.getMembershipBalance()).isZero();
    assertThat(after.getPermanentBalance()).isEqualTo(50);
    assertThat(after.getGiftBalance()).isEqualTo(50);
    assertThat(after.getBalance()).isEqualTo(100);
    assertThat(after.getBalance()).isEqualTo(
        after.getMembershipBalance() + after.getPermanentBalance() + after.getGiftBalance());
  }

  @Test
  void concurrentAvailableDeductionsCannotSpendTheSameBalanceTwice() throws Exception {
    long userId = 88_003L;
    creditMapper.getOrCreateAccount(userId);

    List<CompletableFuture<Integer>> deductions = java.util.stream.LongStream.range(0, 8)
        .mapToObj(index -> CompletableFuture.supplyAsync(() ->
            creditService.deductAvailable(userId, CreditSourceType.TASK, index + 1, 50)))
        .toList();
    CompletableFuture.allOf(deductions.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

    int deducted = deductions.stream().mapToInt(CompletableFuture::join).sum();
    CreditAccount after = creditMapper.findByUserId(userId).orElseThrow();
    assertThat(deducted).isEqualTo(200);
    assertThat(after.getBalance()).isZero();
    assertThat(after.getPermanentBalance()).isZero();
  }
}
