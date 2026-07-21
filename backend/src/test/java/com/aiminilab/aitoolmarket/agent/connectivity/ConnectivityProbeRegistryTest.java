package com.aiminilab.aitoolmarket.agent.connectivity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectivityProbeRegistryTest {

    @Test
    void selectsFirstProbeThatHandlesAccountContextInOrder() {
        List<Integer> calls = new ArrayList<>();
        ProviderConnectivityProbe selected = probe(20, calls, true);
        ProviderConnectivityProbe skipped = probe(10, calls, false);

        Optional<ConnectivityProbeResult> result = new ConnectivityProbeRegistry(List.of(selected, skipped))
                .probeAccount(accountContext());

        assertThat(calls).containsExactly(10, 20);
        assertThat(result).get().extracting(ConnectivityProbeResult::success).isEqualTo(true);
    }

    @Test
    void keepsAccountAndModelStrategiesIndependent() {
        ProviderConnectivityProbe modelProbe = new ProviderConnectivityProbe() {
            @Override
            public int order() {
                return 5;
            }

            @Override
            public Optional<ConnectivityProbeResult> probeModel(ModelProbeContext context) {
                return Optional.of(ConnectivityProbeResult.ok("MODEL_CAPABILITY", 400, 8, "reachable"));
            }
        };
        ConnectivityProbeRegistry registry = new ConnectivityProbeRegistry(List.of(modelProbe));

        assertThat(registry.probeAccount(accountContext())).isEmpty();
        assertThat(registry.probeModel(new ModelProbeContext(
                2L,
                "agnes_images",
                "agnes-image-2.1-flash",
                List.of("IMAGE_GENERATION"),
                "https://apihub.agnes-ai.com/v1",
                "key",
                null
        ))).get().extracting(ConnectivityProbeResult::stage).isEqualTo("MODEL_CAPABILITY");
    }

    private ProviderConnectivityProbe probe(int order, List<Integer> calls, boolean handles) {
        return new ProviderConnectivityProbe() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public Optional<ConnectivityProbeResult> probeAccount(AccountProbeContext context) {
                calls.add(order);
                return handles
                        ? Optional.of(ConnectivityProbeResult.ok("ACCOUNT_MODELS", 200, 12, "ok"))
                        : Optional.empty();
            }
        };
    }

    private AccountProbeContext accountContext() {
        return new AccountProbeContext(
                1L, "agnes", "https://apihub.agnes-ai.com/v1", "key", null, null, null);
    }
}
