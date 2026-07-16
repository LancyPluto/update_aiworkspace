package com.aiminilab.aitoolmarket.admin.proxy;

import java.time.Instant;
import java.util.List;

public record MihomoNodeListResponse(
        boolean managed,
        boolean available,
        String sourceType,
        String selectionMode,
        String selectedNode,
        String activeNode,
        List<MihomoNodeItem> nodes,
        Instant checkedAt,
        String message
) {
}
