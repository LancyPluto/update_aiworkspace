package com.aiminilab.aitoolmarket.credit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:membership_gift_card_pricing_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.production-mode=false"
})
class MembershipGiftCardPricingTest {

    private static final BigDecimal CREDITS_PER_YUAN_AT_1_20_MARKUP = new BigDecimal("120");
    private static final BigDecimal MINIMUM_MARGIN = new BigDecimal("0.30");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void membershipPackagesMatchApprovedPricesCreditsNamesAndPeriodBenefits() {
        List<MembershipPackageExpectation> expected = List.of(
                membership("monthly_starter", "标准版·月卡", 4_000, "59.00", 30, "灵活月付"),
                membership("monthly_growth", "进阶版·月卡", 10_500, "149.00", 30, "灵活月付"),
                membership("monthly_pro", "高级版·月卡", 22_000, "299.00", 30, "灵活月付"),
                membership("monthly_flagship", "豪华版·月卡", 45_000, "599.00", 30, "灵活月付"),
                membership("quarterly_starter", "标准版·季卡", 12_000, "169.00", 90, "季卡约9.5折"),
                membership("quarterly_growth", "进阶版·季卡", 31_500, "425.00", 90, "季卡约9.5折"),
                membership("quarterly_pro", "高级版·季卡", 66_000, "849.00", 90, "季卡约9.5折"),
                membership("quarterly_flagship", "豪华版·季卡", 135_000, "1699.00", 90, "季卡约9.5折"),
                membership("yearly_starter", "标准版·年卡", 48_000, "639.00", 365, "年付立省10%"),
                membership("yearly_growth", "进阶版·年卡", 126_000, "1609.00", 365, "年付立省10%"),
                membership("yearly_pro", "高级版·年卡", 264_000, "3229.00", 365, "年付立省10%"),
                membership("yearly_flagship", "豪华版·年卡", 540_000, "6469.00", 365, "年付立省10%")
        );

        for (MembershipPackageExpectation item : expected) {
            Map<String, Object> actual = jdbcTemplate.queryForMap("""
                    SELECT package_name, credits, price_amount, validity_days, benefits_json, status
                    FROM credit_recharge_packages
                    WHERE package_code = ?
                    """, item.code());
            assertThat(actual.get("package_name")).isEqualTo(item.name());
            assertThat(((Number) actual.get("credits")).intValue()).isEqualTo(item.credits());
            assertThat((BigDecimal) actual.get("price_amount")).isEqualByComparingTo(item.price());
            assertThat(((Number) actual.get("validity_days")).intValue()).isEqualTo(item.validityDays());
            assertThat(actual.get("benefits_json").toString()).contains(item.periodBenefit());
            assertThat(actual.get("status")).isEqualTo("ACTIVE");
        }
    }

    @Test
    void everyPeriodRewardsHigherTiersWithStrictlyLowerUnitPricesAndKeepsThirtyPercentMargin() {
        for (String period : List.of("monthly", "quarterly", "yearly")) {
            List<PricingPoint> points = List.of("starter", "growth", "pro", "flagship").stream()
                    .map(tier -> loadPricingPoint(period + "_" + tier))
                    .toList();

            for (int index = 1; index < points.size(); index++) {
                assertThat(points.get(index).unitPrice())
                        .as("%s unit price must decrease from tier %d to tier %d", period, index, index + 1)
                        .isLessThan(points.get(index - 1).unitPrice());
            }

            for (PricingPoint point : points) {
                BigDecimal estimatedCost = BigDecimal.valueOf(point.credits())
                        .divide(CREDITS_PER_YUAN_AT_1_20_MARKUP, 10, RoundingMode.HALF_UP);
                BigDecimal margin = point.price().subtract(estimatedCost)
                        .divide(point.price(), 10, RoundingMode.HALF_UP);
                assertThat(margin)
                        .as("%s must retain at least 30%% margin", point.code())
                        .isGreaterThanOrEqualTo(MINIMUM_MARGIN);
            }
        }
    }

    @Test
    void memberGiftCardsMatchApprovedValuesWhileOrdinaryCardsRemainUnrestricted() {
        List<MemberGiftExpectation> memberCards = List.of(
                memberGift("member_gift_starter", "标准版会员礼品卡", 4_000, "69.00", "starter"),
                memberGift("member_gift_growth", "进阶版会员礼品卡", 10_500, "169.00", "growth"),
                memberGift("member_gift_pro", "高级版会员礼品卡", 22_000, "339.00", "pro"),
                memberGift("member_gift_flagship", "豪华版会员礼品卡", 45_000, "679.00", "flagship")
        );

        for (MemberGiftExpectation item : memberCards) {
            Map<String, Object> actual = jdbcTemplate.queryForMap("""
                    SELECT package_name, credits, price_amount, card_type, required_member_tier, status
                    FROM gift_card_packages
                    WHERE package_code = ?
                    """, item.code());
            assertThat(actual.get("package_name")).isEqualTo(item.name());
            assertThat(((Number) actual.get("credits")).intValue()).isEqualTo(item.credits());
            assertThat((BigDecimal) actual.get("price_amount")).isEqualByComparingTo(item.price());
            assertThat(actual.get("card_type")).isEqualTo("MEMBER_CREDIT");
            assertThat(actual.get("required_member_tier")).isEqualTo(item.requiredTier());
            assertThat(actual.get("status")).isEqualTo("ACTIVE");
        }

        List<Map<String, Object>> ordinaryCards = jdbcTemplate.queryForList("""
                SELECT package_code, credits, price_amount, card_type, required_member_tier
                FROM gift_card_packages
                WHERE package_code IN ('gift_200', 'gift_500', 'gift_1000', 'gift_3000')
                ORDER BY credits
                """);
        assertThat(ordinaryCards).hasSize(4);
        assertThat(ordinaryCards)
                .extracting(row -> row.get("package_code"), row -> ((Number) row.get("credits")).intValue(),
                        row -> (BigDecimal) row.get("price_amount"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("gift_200", 200, new BigDecimal("4.00")),
                        org.assertj.core.groups.Tuple.tuple("gift_500", 500, new BigDecimal("9.90")),
                        org.assertj.core.groups.Tuple.tuple("gift_1000", 1_000, new BigDecimal("19.60")),
                        org.assertj.core.groups.Tuple.tuple("gift_3000", 3_000, new BigDecimal("58.50"))
                );
        assertThat(ordinaryCards).allSatisfy(row -> {
            assertThat(row.get("card_type")).isEqualTo("CREDIT");
            assertThat(row.get("required_member_tier")).isNull();
        });
    }

    private PricingPoint loadPricingPoint(String packageCode) {
        return jdbcTemplate.queryForObject("""
                SELECT package_code, credits, price_amount
                FROM credit_recharge_packages
                WHERE package_code = ?
                """, (resultSet, rowNum) -> {
            int credits = resultSet.getInt("credits");
            BigDecimal price = resultSet.getBigDecimal("price_amount");
            return new PricingPoint(
                    resultSet.getString("package_code"),
                    credits,
                    price,
                    price.divide(BigDecimal.valueOf(credits), 10, RoundingMode.HALF_UP)
            );
        }, packageCode);
    }

    private static MembershipPackageExpectation membership(String code,
                                                           String name,
                                                           int credits,
                                                           String price,
                                                           int validityDays,
                                                           String periodBenefit) {
        return new MembershipPackageExpectation(code, name, credits, new BigDecimal(price), validityDays, periodBenefit);
    }

    private static MemberGiftExpectation memberGift(String code,
                                                    String name,
                                                    int credits,
                                                    String price,
                                                    String requiredTier) {
        return new MemberGiftExpectation(code, name, credits, new BigDecimal(price), requiredTier);
    }

    private record MembershipPackageExpectation(String code,
                                                String name,
                                                int credits,
                                                BigDecimal price,
                                                int validityDays,
                                                String periodBenefit) {
    }

    private record MemberGiftExpectation(String code,
                                         String name,
                                         int credits,
                                         BigDecimal price,
                                         String requiredTier) {
    }

    private record PricingPoint(String code, int credits, BigDecimal price, BigDecimal unitPrice) {
    }
}
