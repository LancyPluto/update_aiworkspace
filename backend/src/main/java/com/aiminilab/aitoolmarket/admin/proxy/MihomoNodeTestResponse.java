package com.aiminilab.aitoolmarket.admin.proxy;

import java.time.Instant;

public record MihomoNodeTestResponse(
        String nodeName,
        boolean available,
        int latencyMs,
        Instant testedAt,
        String message
) {
}
