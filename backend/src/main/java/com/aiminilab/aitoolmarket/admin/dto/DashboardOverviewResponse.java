package com.aiminilab.aitoolmarket.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardOverviewResponse(
        LocalDate rangeStartDate,
        LocalDate rangeEndDate,
        List<TaskTrendPoint> taskTrend,
        List<ToolUsagePoint> popularTools,
        long apiCreditConsumed,
        BigDecimal rechargeRevenueAmount,
        BigDecimal usageRevenueAmount,
        BigDecimal vendorCostAmount,
        BigDecimal grossProfitAmount,
        BigDecimal grossMarginRate,
        long taskTotal,
        long successTaskTotal,
        long failedTaskTotal,
        long processingTaskTotal,
        BigDecimal successRate,
        long newUserCount,
        long totalUserCount,
        long onlineToolCount,
        long draftToolCount,
        List<BusinessTrendPoint> businessTrend,
        List<ToolContributionPoint> toolContributions
) {
    public record TaskTrendPoint(
            String name,
            long value
    ) {
    }

    public record ToolUsagePoint(
            String name,
            long value
    ) {
    }

    public record BusinessMetrics(
            BigDecimal rechargeRevenueAmount,
            long chargedCredits,
            BigDecimal vendorCostAmount,
            long taskTotal,
            long successTaskTotal,
            long failedTaskTotal,
            long processingTaskTotal,
            long newUserCount,
            long totalUserCount,
            long onlineToolCount,
            long draftToolCount
    ) {
    }

    public record BusinessTrendRawPoint(
            LocalDate day,
            BigDecimal rechargeRevenueAmount,
            long chargedCredits,
            BigDecimal vendorCostAmount,
            long taskTotal
    ) {
    }

    public record BusinessTrendPoint(
            String name,
            BigDecimal rechargeRevenueAmount,
            BigDecimal usageRevenueAmount,
            BigDecimal vendorCostAmount,
            BigDecimal grossProfitAmount,
            long taskTotal
    ) {
    }

    public record ToolContributionRawPoint(
            String toolName,
            long taskTotal,
            long successTaskTotal,
            long failedTaskTotal,
            long chargedCredits,
            BigDecimal vendorCostAmount
    ) {
    }

    public record ToolContributionPoint(
            String toolName,
            long taskTotal,
            long successTaskTotal,
            long failedTaskTotal,
            BigDecimal usageRevenueAmount,
            BigDecimal vendorCostAmount,
            BigDecimal grossProfitAmount,
            BigDecimal successRate
    ) {
    }
}
