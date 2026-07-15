package com.aiminilab.aitoolmarket.admin.proxy;

public interface ProxyConnectionProbe {
    ProxyTestResponse testSubscription(String subscriptionUrl);

    ProxyTestResponse testManual(String host, int port);
}
