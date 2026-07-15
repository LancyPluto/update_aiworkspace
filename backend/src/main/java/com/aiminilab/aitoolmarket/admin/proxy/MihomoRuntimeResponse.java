package com.aiminilab.aitoolmarket.admin.proxy;

import java.time.Instant;

public record MihomoRuntimeResponse(
        boolean managed,
        boolean available,
        String version,
        Instant lastAppliedAt,
        String message
) {
}
