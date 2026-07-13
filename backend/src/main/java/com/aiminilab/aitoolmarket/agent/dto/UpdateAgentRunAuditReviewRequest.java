package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.Size;

public record UpdateAgentRunAuditReviewRequest(
        @Size(max = 128) String expectedToolCode,
        @Size(max = 64) String finalCategory,
        @Size(max = 4000) String reviewNote
) {}
