package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.common.util.UrlSecurityValidator;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class DefaultProxyConnectionProbe implements ProxyConnectionProbe {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    @Override
    public ProxyTestResponse testSubscription(String subscriptionUrl) {
        long started = System.nanoTime();
        String target = safeHost(subscriptionUrl);
        try {
            UrlSecurityValidator.validate(subscriptionUrl);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(subscriptionUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "text/yaml,text/plain,application/octet-stream,*/*")
                    .header("User-Agent", "AI-Tool-Market-Proxy-Probe/1.0")
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long latency = elapsedMillis(started);
            boolean success = response.statusCode() >= 200 && response.statusCode() < 400;
            String message = success
                    ? "订阅地址可访问，HTTP " + response.statusCode()
                    : "订阅地址返回 HTTP " + response.statusCode();
            return new ProxyTestResponse(success, latency, message, target);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ProxyTestResponse(false, elapsedMillis(started), "连接测试已中断", target);
        } catch (Exception exception) {
            return new ProxyTestResponse(false, elapsedMillis(started), conciseMessage(exception), target);
        }
    }

    @Override
    public ProxyTestResponse testManual(String host, int port) {
        long started = System.nanoTime();
        String target = host + ":" + port;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT.toMillis());
            return new ProxyTestResponse(true, elapsedMillis(started), "代理端口可连接", target);
        } catch (Exception exception) {
            return new ProxyTestResponse(false, elapsedMillis(started), conciseMessage(exception), target);
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String safeHost(String rawUrl) {
        try {
            String host = URI.create(rawUrl).getHost();
            return host == null ? "订阅地址" : host;
        } catch (Exception ignored) {
            return "订阅地址";
        }
    }

    private static String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "连接失败：" + exception.getClass().getSimpleName()
                : "连接失败：" + message;
    }
}
