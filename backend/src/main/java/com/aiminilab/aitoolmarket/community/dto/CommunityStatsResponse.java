package com.aiminilab.aitoolmarket.community.dto;

import java.util.List;

public record CommunityStatsResponse(
        Long postCount,
        Long pendingCount,
        Long hiddenCount,
        Long impressionCount,
        Long detailViewCount,
        Long sameStyleClickCount,
        Long taskCreatedCount,
        Long creditSpent,
        List<MetricPoint> topTools,
        List<MetricPoint> topTopics,
        List<MetricPoint> topCreators
) {
    public record MetricPoint(String name, Long value) {
    }
}
