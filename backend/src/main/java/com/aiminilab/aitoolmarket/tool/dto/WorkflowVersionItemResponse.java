package com.aiminilab.aitoolmarket.tool.dto;

import java.time.LocalDateTime;

public record WorkflowVersionItemResponse(
        Long id,
        int version,
        String snapshotLabel,
        Long createdBy,
        LocalDateTime createdAt
) {}
