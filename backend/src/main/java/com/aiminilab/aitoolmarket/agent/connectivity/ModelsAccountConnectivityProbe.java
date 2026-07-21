package com.aiminilab.aitoolmarket.agent.connectivity;

import com.aiminilab.aitoolmarket.agent.connectivity.AccountProbeErrorClassifier.Classification;
import com.aiminilab.aitoolmarket.agent.support.OutboundHttpClientFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class ModelsAccountConnectivityProbe implements ProviderConnectivityProbe {

    private static final String STAGE = "ACCOUNT_MODELS";

    private final ModelsEndpointResolver endpointResolver;
    private final AccountProbeErrorClassifier errorClassifier;

    public ModelsAccountConnectivityProbe(
            ModelsEndpointResolver endpointResolver,
            AccountProbeErrorClassifier errorClassifier
    ) {
        this.endpointResolver = endpointResolver;
        this.errorClassifier = errorClassifier;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Optional<ConnectivityProbeResult> probeAccount(AccountProbeContext context) {
        if (!endpointResolver.supports(context.vendorCode())) {
            return Optional.empty();
        }
        Optional<String> endpoint = endpointResolver.resolve(context.vendorCode(), context.baseUrl());
        if (endpoint.isEmpty()) {
            return Optional.of(failed(null, 0L, "账户 Base URL 无效，无法解析模型列表地址"));
        }
        if (context.apiKey() == null || context.apiKey().isBlank()) {
            return Optional.of(failed(null, 0L, "API Key 未配置"));
        }

        long startedAt = System.nanoTime();
        try {
            HttpClient client = OutboundHttpClientFactory.create(Duration.ofSeconds(8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.get()))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + context.apiKey().trim())
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = elapsedMillis(startedAt);
            Classification classification = errorClassifier.classify(response.statusCode(), response.body());
            return switch (classification.decision()) {
                case PASS -> Optional.of(ConnectivityProbeResult.ok(
                        STAGE, response.statusCode(), latencyMs, classification.message()));
                case WARNING -> Optional.of(ConnectivityProbeResult.warning(
                        STAGE, response.statusCode(), latencyMs, classification.message()));
                case FAIL -> Optional.of(failed(response.statusCode(), latencyMs, classification.message()));
                case FALLBACK -> Optional.of(failed(
                        response.statusCode(),
                        latencyMs,
                        classification.message() + "，Agnes 未配置其他账户级探活方式"
                ));
            };
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.of(failed(null, elapsedMillis(startedAt), "账户网关连接被中断"));
        } catch (Exception exception) {
            String detail = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return Optional.of(failed(null, elapsedMillis(startedAt), "账户网关连接失败：" + detail));
        }
    }

    private ConnectivityProbeResult failed(Integer httpStatus, long latencyMs, String message) {
        return new ConnectivityProbeResult(false, STAGE, httpStatus, latencyMs, message, false, null);
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }
}
