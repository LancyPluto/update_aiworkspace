package com.aiminilab.aitoolmarket.admin.proxy;

public interface ProxyPathProbe {
    ProxyPathProbeResult probe(String domain, String probeUrl, ProxyEgressPath path, int timeoutMs);
}
