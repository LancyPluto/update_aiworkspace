package com.aiminilab.aitoolmarket.ppt.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

public record PptProjectView(
        Long projectId,
        String title,
        String topic,
        String creationType,
        String language,
        String aspectRatio,
        Integer pageCount,
        String status,
        String engineStrategy,
        JsonNode latestDeck,
        List<PptJobView> recentJobs,
        List<PptExportView> exports,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
