package com.aiminilab.aitoolmarket.admin.dto;

import java.util.List;

public record DashboardOverviewResponse(
        List<TaskTrendPoint> taskTrend,
        List<ToolUsagePoint> popularTools,
        long apiCreditConsumed
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
}
