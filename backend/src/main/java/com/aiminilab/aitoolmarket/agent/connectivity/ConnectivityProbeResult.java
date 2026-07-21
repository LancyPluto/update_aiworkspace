package com.aiminilab.aitoolmarket.agent.connectivity;

public record ConnectivityProbeResult(
        boolean success,
        String stage,
        Integer httpStatus,
        long latencyMs,
        String message,
        boolean fallbackUsed,
        String warning
) {
    public static ConnectivityProbeResult ok(
            String stage,
            Integer httpStatus,
            long latencyMs,
            String message
    ) {
        return new ConnectivityProbeResult(true, stage, httpStatus, latencyMs, message, false, null);
    }

    public static ConnectivityProbeResult warning(
            String stage,
            Integer httpStatus,
            long latencyMs,
            String message
    ) {
        return new ConnectivityProbeResult(true, stage, httpStatus, latencyMs, message, false, message);
    }
}
