package com.aiminilab.aitoolmarket.agent.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Outbound HTTP client for vendor connectivity probes.
 * Honors HTTP(S)_PROXY from the container environment (e.g. mihomo mixed-port on production).
 */
public final class OutboundHttpClientFactory {

    private OutboundHttpClientFactory() {
    }

    public static HttpClient create(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySelector selector = resolveProxySelector();
        if (selector != null) {
            builder.proxy(selector);
        }
        return builder.build();
    }

    static ProxySelector resolveProxySelector() {
        String proxyUrl = firstNonBlank(System.getenv("HTTPS_PROXY"), System.getenv("HTTP_PROXY"));
        if (proxyUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(proxyUrl);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 7890;
            if (host == null || host.isBlank()) {
                return null;
            }
            InetSocketAddress address = InetSocketAddress.createUnresolved(host, port);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, address);
            return new ProxySelector() {
                @Override
                public List<Proxy> select(URI target) {
                    return List.of(proxy);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    // no-op
                }
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
