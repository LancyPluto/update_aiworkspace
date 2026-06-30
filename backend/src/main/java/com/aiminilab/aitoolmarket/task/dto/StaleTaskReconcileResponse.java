package com.aiminilab.aitoolmarket.task.dto;

import java.util.List;

public record StaleTaskReconcileResponse(
        int staleMinutes,
        int inspected,
        int timedOut,
        List<Long> taskIds
) {
}
