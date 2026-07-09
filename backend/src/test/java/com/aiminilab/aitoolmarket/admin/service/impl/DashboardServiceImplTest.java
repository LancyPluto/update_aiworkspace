package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.BusinessMetrics;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.BusinessTrendRawPoint;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.ToolContributionRawPoint;
import com.aiminilab.aitoolmarket.admin.mapper.DashboardMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-08T04:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void overviewCalculatesBusinessMetricsAndFillsMissingTrendDays() {
        DashboardMapper mapper = mock(DashboardMapper.class);
        when(mapper.selectTaskTrend()).thenReturn(List.of(new DashboardOverviewResponse.TaskTrendPoint("2026-07", 10)));
        when(mapper.selectPopularTools()).thenReturn(List.of(new DashboardOverviewResponse.ToolUsagePoint("GPT-image2", 8)));
        when(mapper.sumConsumedCredits()).thenReturn(420L);
        when(mapper.selectBusinessMetrics(any(), any())).thenReturn(new BusinessMetrics(
                new BigDecimal("120.50"),
                1500,
                new BigDecimal("3.25"),
                10,
                8,
                1,
                1,
                2,
                99,
                7,
                3
        ));
        when(mapper.selectBusinessTrend(any(), any())).thenReturn(List.of(
                new BusinessTrendRawPoint(LocalDate.of(2026, 6, 9), new BigDecimal("30.00"), 500, new BigDecimal("1.25"), 3),
                new BusinessTrendRawPoint(LocalDate.of(2026, 7, 8), BigDecimal.ZERO, 1000, new BigDecimal("2.00"), 7)
        ));
        when(mapper.selectToolContributions(any(), any())).thenReturn(List.of(
                new ToolContributionRawPoint("GPT-image2", 10, 8, 1, 1500, new BigDecimal("3.25"))
        ));

        DashboardOverviewResponse response = new DashboardServiceImpl(mapper, FIXED_CLOCK).overview();

        assertThat(response.rangeStartDate()).isEqualTo(LocalDate.of(2026, 6, 9));
        assertThat(response.rangeEndDate()).isEqualTo(LocalDate.of(2026, 7, 8));
        assertThat(response.rechargeRevenueAmount()).isEqualByComparingTo("120.50");
        assertThat(response.usageRevenueAmount()).isEqualByComparingTo("15.00");
        assertThat(response.vendorCostAmount()).isEqualByComparingTo("3.25");
        assertThat(response.grossProfitAmount()).isEqualByComparingTo("11.75");
        assertThat(response.grossMarginRate()).isEqualByComparingTo("0.7833");
        assertThat(response.successRate()).isEqualByComparingTo("0.8000");
        assertThat(response.businessTrend()).hasSize(30);
        assertThat(response.businessTrend().get(0).name()).isEqualTo("2026-06-09");
        assertThat(response.businessTrend().get(0).grossProfitAmount()).isEqualByComparingTo("3.75");
        assertThat(response.businessTrend().get(1).rechargeRevenueAmount()).isEqualByComparingTo("0.00");
        assertThat(response.businessTrend().get(29).usageRevenueAmount()).isEqualByComparingTo("10.00");
        assertThat(response.toolContributions()).singleElement().satisfies(tool -> {
            assertThat(tool.toolName()).isEqualTo("GPT-image2");
            assertThat(tool.usageRevenueAmount()).isEqualByComparingTo("15.00");
            assertThat(tool.grossProfitAmount()).isEqualByComparingTo("11.75");
            assertThat(tool.successRate()).isEqualByComparingTo("0.8000");
        });

        verify(mapper).selectBusinessMetrics(
                LocalDateTime.of(2026, 6, 9, 0, 0),
                LocalDateTime.of(2026, 7, 9, 0, 0)
        );
    }

    @Test
    void overviewReturnsZeroDefaultsWhenBusinessDataIsMissing() {
        DashboardMapper mapper = mock(DashboardMapper.class);
        when(mapper.selectTaskTrend()).thenReturn(List.of());
        when(mapper.selectPopularTools()).thenReturn(List.of());
        when(mapper.selectBusinessMetrics(any(), any())).thenReturn(null);
        when(mapper.selectBusinessTrend(any(), any())).thenReturn(null);
        when(mapper.selectToolContributions(any(), any())).thenReturn(null);

        DashboardOverviewResponse response = new DashboardServiceImpl(mapper, FIXED_CLOCK).overview();

        assertThat(response.rechargeRevenueAmount()).isEqualByComparingTo("0.00");
        assertThat(response.usageRevenueAmount()).isEqualByComparingTo("0.00");
        assertThat(response.vendorCostAmount()).isEqualByComparingTo("0.00");
        assertThat(response.grossProfitAmount()).isEqualByComparingTo("0.00");
        assertThat(response.grossMarginRate()).isEqualByComparingTo("0.0000");
        assertThat(response.successRate()).isEqualByComparingTo("0.0000");
        assertThat(response.businessTrend()).hasSize(30);
        assertThat(response.businessTrend()).allSatisfy(point -> {
            assertThat(point.rechargeRevenueAmount()).isEqualByComparingTo("0.00");
            assertThat(point.usageRevenueAmount()).isEqualByComparingTo("0.00");
            assertThat(point.vendorCostAmount()).isEqualByComparingTo("0.00");
            assertThat(point.grossProfitAmount()).isEqualByComparingTo("0.00");
            assertThat(point.taskTotal()).isZero();
        });
        assertThat(response.toolContributions()).isEmpty();
    }
}
