package com.aiminilab.aitoolmarket.admin.proxy;

public record ProxyAutoDecision(
        String selectedPath,
        String reason,
        int sampleCount,
        int timeoutMs,
        int switchThresholdMs,
        int hysteresisMs,
        int cooldownSeconds
) {
}
