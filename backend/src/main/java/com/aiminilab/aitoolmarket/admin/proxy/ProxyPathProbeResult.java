package com.aiminilab.aitoolmarket.admin.proxy;

public record ProxyPathProbeResult(
        String path,
        boolean success,
        long dnsMs,
        long tcpMs,
        long tlsMs,
        long httpMs,
        long totalMs,
        Integer httpStatus,
        String error,
        double successRate,
        int sampleCount
) {
    public ProxyPathProbeResult withHistory(double rate, int samples) {
        return new ProxyPathProbeResult(
                path, success, dnsMs, tcpMs, tlsMs, httpMs, totalMs,
                httpStatus, error, rate, samples
        );
    }
}
