package com.aiminilab.aitoolmarket.agent.connectivity;

import java.util.Optional;

public interface ProviderConnectivityProbe {

    int order();

    default Optional<ConnectivityProbeResult> probeAccount(AccountProbeContext context) {
        return Optional.empty();
    }

    default Optional<ConnectivityProbeResult> probeModel(ModelProbeContext context) {
        return Optional.empty();
    }
}
