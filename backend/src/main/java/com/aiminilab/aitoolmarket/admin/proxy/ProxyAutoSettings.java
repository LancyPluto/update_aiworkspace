package com.aiminilab.aitoolmarket.admin.proxy;

public record ProxyAutoSettings(
        int timeoutMs,
        int sampleSize,
        int switchThresholdMs,
        int hysteresisMs,
        int cooldownSeconds
) {
    public static ProxyAutoSettings defaults() {
        return new ProxyAutoSettings(5_000, 6, 150, 80, 300);
    }
}
