package com.aiminilab.aitoolmarket.tool.dto;

import java.time.LocalDateTime;

public record WorkflowVersionItemResponse(
        Long id,
        int version,
        String dslHash,
        Long sourceDraftRevision,
        String snapshotLabel,
        Long publishedBy,
        LocalDateTime publishedAt
) {}
