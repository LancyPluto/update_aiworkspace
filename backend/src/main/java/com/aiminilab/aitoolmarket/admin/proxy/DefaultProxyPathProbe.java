package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.common.util.UrlSecurityValidator;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class DefaultProxyPathProbe implements ProxyPathProbe {
    private static final String MIHOMO_HOST = "mihomo";
    private static final int MIHOMO_PORT = 7890;

    @Override
    public ProxyPathProbeResult probe(String domain, String probeUrl, ProxyEgressPath path, int timeoutMs) {
        long totalStarted = System.nanoTime();
        long dnsMs = 0;
        long tcpMs = 0;
        long tlsMs = 0;
        long httpMs = 0;
        try {
            UrlSecurityValidator.validate(probeUrl);
            URI uri = URI.create(probeUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            String connectHost = path == ProxyEgressPath.DIRECT ? domain : MIHOMO_HOST;
            int connectPort = path == ProxyEgressPath.DIRECT ? port : MIHOMO_PORT;

            long stage = System.nanoTime();
            InetAddress.getAllByName(connectHost);
            dnsMs = elapsed(stage);

            stage = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(connectHost, connectPort), timeoutMs);
            }
            tcpMs = elapsed(stage);

            if (path == ProxyEgressPath.DIRECT) {
                stage = System.nanoTime();
                try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
                    socket.connect(new InetSocketAddress(domain, port), timeoutMs);
                    socket.setSoTimeout(timeoutMs);
                    socket.startHandshake();
                }
                tlsMs = elapsed(stage);
            }

            HttpClient.Builder client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER);
            if (path == ProxyEgressPath.PROXY) {
                client.proxy(ProxySelector.of(new InetSocketAddress(MIHOMO_HOST, MIHOMO_PORT)));
            } else {
                client.proxy(new NoProxySelector());
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "AI-Tool-Market-Egress-Probe/1.0")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            stage = System.nanoTime();
            HttpResponse<Void> response = client.build().send(request, HttpResponse.BodyHandlers.discarding());
            httpMs = elapsed(stage);
            boolean success = isSuccessfulHttpStatus(response.statusCode());
            return new ProxyPathProbeResult(
                    path.name(), success, dnsMs, tcpMs, tlsMs, httpMs, elapsed(totalStarted),
                    response.statusCode(), success ? "" : errorForHttpStatus(response.statusCode()), 0, 0
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(path, dnsMs, tcpMs, tlsMs, httpMs, totalStarted, "interrupted");
        } catch (com.aiminilab.aitoolmarket.common.exception.BusinessException exception) {
            return failed(path, dnsMs, tcpMs, tlsMs, httpMs, totalStarted, "target_rejected");
        } catch (java.net.UnknownHostException exception) {
            return failed(path, dnsMs, tcpMs, tlsMs, httpMs, totalStarted, "dns_failed");
        } catch (java.net.SocketTimeoutException | java.net.http.HttpTimeoutException exception) {
            return failed(path, dnsMs, tcpMs, tlsMs, httpMs, totalStarted, "timeout");
        } catch (javax.net.ssl.SSLException exception) {
            return failed(path, dnsMs, tcpMs, tlsMs, httpMs, totalStarted, "tls_failed");
        } catch (Exception exception) {
            return failed(path, dnsMs, tcpMs, tlsMs, httpMs, totalStarted, "connection_failed");
        }
    }

    static boolean isSuccessfulHttpStatus(int statusCode) {
        return statusCode >= 100 && statusCode < 300 || statusCode >= 400 && statusCode < 500;
    }

    static String errorForHttpStatus(int statusCode) {
        return statusCode >= 300 && statusCode < 400 ? "redirect_rejected" : "upstream_http_error";
    }

    private ProxyPathProbeResult failed(
            ProxyEgressPath path,
            long dnsMs,
            long tcpMs,
            long tlsMs,
            long httpMs,
            long started,
            String error
    ) {
        return new ProxyPathProbeResult(
                path.name(), false, dnsMs, tcpMs, tlsMs, httpMs, elapsed(started),
                null, error, 0, 0
        );
    }

    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private static class NoProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
        }
    }
}
