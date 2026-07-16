package com.aiminilab.aitoolmarket.admin.proxy;

public record MihomoNodeItem(
        String name,
        String type,
        boolean available,
        int latencyMs,
        boolean selected
) {
}
