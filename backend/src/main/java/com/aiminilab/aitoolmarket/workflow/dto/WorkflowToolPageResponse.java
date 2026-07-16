package com.aiminilab.aitoolmarket.workflow.dto;

import java.util.List;

public record WorkflowToolPageResponse(
        List<WorkflowToolSummaryResponse> items,
        long total,
        int page,
        int pageSize,
        boolean hasNext
) {
}
