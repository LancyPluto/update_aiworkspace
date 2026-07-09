package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.BusinessMetrics;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.BusinessTrendPoint;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.BusinessTrendRawPoint;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.ToolContributionPoint;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.ToolContributionRawPoint;
import com.aiminilab.aitoolmarket.admin.mapper.DashboardMapper;
import com.aiminilab.aitoolmarket.admin.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DashboardServiceImpl implements DashboardService {
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final BigDecimal CREDIT_PRICE_CNY = new BigDecimal("0.01");

    private final DashboardMapper dashboardMapper;
    private final Clock clock;

    @Autowired
    public DashboardServiceImpl(DashboardMapper dashboardMapper) {
        this(dashboardMapper, Clock.systemDefaultZone());
    }

    DashboardServiceImpl(DashboardMapper dashboardMapper, Clock clock) {
        this.dashboardMapper = dashboardMapper;
        this.clock = clock;
    }

    @Override
    public DashboardOverviewResponse overview() {
        LocalDate endDate = LocalDate.now(clock);
        LocalDate startDate = endDate.minusDays(DEFAULT_RANGE_DAYS - 1L);
        LocalDateTime startAt = startDate.atStartOfDay();
        LocalDateTime endAt = endDate.plusDays(1).atStartOfDay();

        BusinessMetrics metrics = defaultMetrics(dashboardMapper.selectBusinessMetrics(startAt, endAt));
        BigDecimal rechargeRevenueAmount = money(metrics.rechargeRevenueAmount());
        BigDecimal usageRevenueAmount = creditsToMoney(metrics.chargedCredits());
        BigDecimal vendorCostAmount = money(metrics.vendorCostAmount());
        BigDecimal grossProfitAmount = usageRevenueAmount.subtract(vendorCostAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grossMarginRate = ratio(grossProfitAmount, usageRevenueAmount);
        BigDecimal successRate = ratio(BigDecimal.valueOf(metrics.successTaskTotal()), BigDecimal.valueOf(metrics.taskTotal()));

        return new DashboardOverviewResponse(
                startDate,
                endDate,
                dashboardMapper.selectTaskTrend(),
                dashboardMapper.selectPopularTools(),
                dashboardMapper.sumConsumedCredits(),
                rechargeRevenueAmount,
                usageRevenueAmount,
                vendorCostAmount,
                grossProfitAmount,
                grossMarginRate,
                metrics.taskTotal(),
                metrics.successTaskTotal(),
                metrics.failedTaskTotal(),
                metrics.processingTaskTotal(),
                successRate,
                metrics.newUserCount(),
                metrics.totalUserCount(),
                metrics.onlineToolCount(),
                metrics.draftToolCount(),
                buildBusinessTrend(startDate, endDate, dashboardMapper.selectBusinessTrend(startAt, endAt)),
                buildToolContributions(dashboardMapper.selectToolContributions(startAt, endAt))
        );
    }

    private List<BusinessTrendPoint> buildBusinessTrend(LocalDate startDate, LocalDate endDate,
                                                        List<BusinessTrendRawPoint> rawPoints) {
        Map<LocalDate, BusinessTrendRawPoint> pointsByDay = defaultList(rawPoints).stream()
                .filter(point -> point.day() != null)
                .collect(Collectors.toMap(BusinessTrendRawPoint::day, Function.identity(), (left, right) -> right));
        return Stream.iterate(startDate, day -> !day.isAfter(endDate), day -> day.plusDays(1))
                .map(day -> {
                    BusinessTrendRawPoint raw = pointsByDay.get(day);
                    BigDecimal usageRevenue = creditsToMoney(raw == null ? 0 : raw.chargedCredits());
                    BigDecimal cost = money(raw == null ? null : raw.vendorCostAmount());
                    return new BusinessTrendPoint(
                            day.toString(),
                            money(raw == null ? null : raw.rechargeRevenueAmount()),
                            usageRevenue,
                            cost,
                            usageRevenue.subtract(cost).setScale(2, RoundingMode.HALF_UP),
                            raw == null ? 0 : raw.taskTotal()
                    );
                })
                .toList();
    }

    private List<ToolContributionPoint> buildToolContributions(List<ToolContributionRawPoint> rawPoints) {
        return defaultList(rawPoints).stream()
                .map(raw -> {
                    BigDecimal usageRevenue = creditsToMoney(raw.chargedCredits());
                    BigDecimal cost = money(raw.vendorCostAmount());
                    return new ToolContributionPoint(
                            raw.toolName(),
                            raw.taskTotal(),
                            raw.successTaskTotal(),
                            raw.failedTaskTotal(),
                            usageRevenue,
                            cost,
                            usageRevenue.subtract(cost).setScale(2, RoundingMode.HALF_UP),
                            ratio(BigDecimal.valueOf(raw.successTaskTotal()), BigDecimal.valueOf(raw.taskTotal()))
                    );
                })
                .toList();
    }

    private BusinessMetrics defaultMetrics(BusinessMetrics metrics) {
        return metrics == null
                ? new BusinessMetrics(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0, 0)
                : metrics;
    }

    private <T> List<T> defaultList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private BigDecimal creditsToMoney(long credits) {
        return BigDecimal.valueOf(Math.max(0, credits)).multiply(CREDIT_PRICE_CNY).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }
}
