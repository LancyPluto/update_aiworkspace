package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrderItem;
import com.aiminilab.aitoolmarket.credit.service.GiftCardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gift_card_issuance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.production-mode=false"
})
class GiftCardIssuanceIdempotencyTest {
    @Autowired
    private GiftCardService giftCardService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentCompensationIssuesExactRequestedCardCount() throws Exception {
        long userId = 92_001L;
        long orderId = 6_001L;
        CreditRechargeOrderItem item = new CreditRechargeOrderItem();
        item.setId(7_001L);
        item.setOrderId(orderId);
        item.setGiftCardPackageId(1L);
        item.setQuantity(3);
        item.setCredits(200);

        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> giftCardService.createGiftCardsFromOrderItems(userId, orderId, List.of(item)));
        CompletableFuture<Void> second = CompletableFuture.runAsync(
                () -> giftCardService.createGiftCardsFromOrderItems(userId, orderId, List.of(item)));
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gift_cards WHERE recharge_order_id = ?", Integer.class, orderId))
                .isEqualTo(3);
    }
}
