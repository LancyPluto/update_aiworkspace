package com.aiminilab.aitoolmarket.agent.connectivity;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class ConnectivityProbeRegistry {

    private final List<ProviderConnectivityProbe> probes;

    public ConnectivityProbeRegistry(List<ProviderConnectivityProbe> probes) {
        this.probes = probes.stream()
                .sorted(Comparator.comparingInt(ProviderConnectivityProbe::order))
                .toList();
    }

    public Optional<ConnectivityProbeResult> probeAccount(AccountProbeContext context) {
        for (ProviderConnectivityProbe probe : probes) {
            Optional<ConnectivityProbeResult> result = probe.probeAccount(context);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    public Optional<ConnectivityProbeResult> probeModel(ModelProbeContext context) {
        for (ProviderConnectivityProbe probe : probes) {
            Optional<ConnectivityProbeResult> result = probe.probeModel(context);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }
}
