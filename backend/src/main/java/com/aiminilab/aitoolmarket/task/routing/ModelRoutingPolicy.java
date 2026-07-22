package com.aiminilab.aitoolmarket.task.routing;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.task.dto.RouteFailoverRequest;
import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ModelRoutingPolicy {
    private ModelRoutingPolicy() {
    }

    public record Candidate(
            AgentModelConfig modelConfig,
            ModelVendorAccount account,
            AccountModelRouteState state
    ) {
    }

    public static boolean compatible(AgentModelConfig reference,
                                     AgentModelConfig candidate,
                                     ModelCapabilityService capabilityService,
                                     ObjectMapper objectMapper) {
        return incompatibilityReason(reference, candidate, capabilityService, objectMapper) == null;
    }

    public static boolean compatible(AgentModelConfig reference,
                                     AgentModelConfig candidate,
                                     ModelCapabilityService capabilityService,
                                     ObjectMapper objectMapper,
                                     List<String> requiredCapabilities) {
        return incompatibilityReason(
                reference, candidate, capabilityService, objectMapper, requiredCapabilities) == null;
    }

    public static String incompatibilityReason(AgentModelConfig reference,
                                               AgentModelConfig candidate,
                                               ModelCapabilityService capabilityService,
                                               ObjectMapper objectMapper) {
        return incompatibilityReason(reference, candidate, capabilityService, objectMapper, List.of());
    }

    public static String incompatibilityReason(AgentModelConfig reference,
                                               AgentModelConfig candidate,
                                               ModelCapabilityService capabilityService,
                                               ObjectMapper objectMapper,
                                               List<String> requiredCapabilities) {
        if (reference == null || candidate == null) {
            return "ROUTE_CONFIG_MISMATCH";
        }
        List<String> referenceCapabilities = normalizedCapabilities(reference, capabilityService);
        List<String> candidateCapabilities = normalizedCapabilities(candidate, capabilityService);
        List<String> normalizedRequirements = normalizedCapabilities(requiredCapabilities);
        boolean capabilitiesCompatible = normalizedRequirements.isEmpty()
                ? referenceCapabilities.equals(candidateCapabilities)
                : referenceCapabilities.containsAll(normalizedRequirements)
                    && candidateCapabilities.containsAll(normalizedRequirements);
        boolean routeCompatible = normalized(reference.getProvider()).equals(normalized(candidate.getProvider()))
                && Objects.equals(reference.getModelName(), candidate.getModelName())
                && normalized(reference.getExecutionTask()).equals(normalized(candidate.getExecutionTask()))
                && jsonEquivalent(reference.getExecutionOptionsJson(), candidate.getExecutionOptionsJson(), objectMapper)
                && capabilitiesCompatible;
        if (!routeCompatible) {
            return "ROUTE_CONFIG_MISMATCH";
        }
        boolean priceCompatible = normalized(reference.getBillingUnit()).equals(normalized(candidate.getBillingUnit()))
                && decimalEquivalent(reference.getUnitPrice(), candidate.getUnitPrice())
                && decimalEquivalent(reference.getInputTokenPricePer1k(), candidate.getInputTokenPricePer1k())
                && decimalEquivalent(reference.getOutputTokenPricePer1k(), candidate.getOutputTokenPricePer1k())
                && decimalEquivalent(reference.getInputTokenPricePer1m(), candidate.getInputTokenPricePer1m())
                && decimalEquivalent(reference.getOutputTokenPricePer1m(), candidate.getOutputTokenPricePer1m());
        return priceCompatible ? null : "PRICE_MISMATCH";
    }

    public static Candidate choose(List<Candidate> candidates, LocalDateTime now) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> circuitAllowsSelection(candidate.state(), now))
                .min(weightedLeastInFlightComparator())
                .orElse(null);
    }

    public static List<AgentModelConfig> deduplicateByAccount(List<AgentModelConfig> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, AgentModelConfig> byAccount = new LinkedHashMap<>();
        candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.getVendorAccountId() != null)
                .sorted(Comparator.comparing(AgentModelConfig::getId,
                        Comparator.nullsLast(Long::compareTo)))
                .forEach(candidate -> byAccount.putIfAbsent(candidate.getVendorAccountId(), candidate));
        return List.copyOf(byAccount.values());
    }

    public static boolean canFailover(RouteFailoverRequest request,
                                      boolean hasProviderCheckpoint,
                                      String persistedProviderRequestId,
                                      Boolean persistedProviderCharged) {
        if (request == null || !"ACCOUNT".equalsIgnoreCase(request.retryScope())) {
            return false;
        }
        String deliveryState = normalized(request.deliveryState());
        if (!"not_sent".equals(deliveryState) && !"rejected".equals(deliveryState)) {
            return false;
        }
        return !hasProviderCheckpoint
                && isBlank(persistedProviderRequestId)
                && !Boolean.TRUE.equals(persistedProviderCharged)
                && isBlank(request.providerRequestId())
                && !Boolean.TRUE.equals(request.providerCharged());
    }

    private static Comparator<Candidate> weightedLeastInFlightComparator() {
        return (left, right) -> {
            long leftLoad = (long) inFlight(left.state()) + 1L;
            long rightLoad = (long) inFlight(right.state()) + 1L;
            long leftScaled = leftLoad * weight(right.account());
            long rightScaled = rightLoad * weight(left.account());
            int scoreComparison = Long.compare(leftScaled, rightScaled);
            if (scoreComparison != 0) {
                return scoreComparison;
            }
            int timeComparison = Comparator.nullsFirst(LocalDateTime::compareTo)
                    .compare(left.state().getLastSelectedAt(), right.state().getLastSelectedAt());
            if (timeComparison != 0) {
                return timeComparison;
            }
            return Comparator.nullsLast(Long::compareTo)
                    .compare(left.modelConfig().getId(), right.modelConfig().getId());
        };
    }

    public static boolean circuitAllowsSelection(AccountModelRouteState state, LocalDateTime now) {
        if (state == null || "CLOSED".equalsIgnoreCase(state.getCircuitStatus())) {
            return true;
        }
        if ("HALF_OPEN".equalsIgnoreCase(state.getCircuitStatus())) {
            return inFlight(state) == 0;
        }
        return "OPEN".equalsIgnoreCase(state.getCircuitStatus())
                && (state.getCooldownUntil() == null || !state.getCooldownUntil().isAfter(now))
                && inFlight(state) == 0;
    }

    private static List<String> normalizedCapabilities(AgentModelConfig config,
                                                       ModelCapabilityService capabilityService) {
        return normalizedCapabilities(capabilityService.resolveCapabilities(config));
    }

    private static List<String> normalizedCapabilities(List<String> capabilities) {
        return (capabilities == null ? List.<String>of() : capabilities).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean jsonEquivalent(String left, String right, ObjectMapper objectMapper) {
        if (isBlank(left) || isBlank(right)) {
            return isBlank(left) && isBlank(right);
        }
        try {
            JsonNode leftNode = objectMapper.readTree(left);
            JsonNode rightNode = objectMapper.readTree(right);
            return Objects.equals(leftNode, rightNode);
        } catch (Exception ignored) {
            return left.trim().equals(right.trim());
        }
    }

    private static boolean decimalEquivalent(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.compareTo(right) == 0;
    }

    private static int inFlight(AccountModelRouteState state) {
        return state == null || state.getInFlightCount() == null ? 0 : Math.max(0, state.getInFlightCount());
    }

    private static int weight(ModelVendorAccount account) {
        if (account == null || account.getLoadBalanceWeight() == null) {
            return 100;
        }
        return Math.max(1, Math.min(100, account.getLoadBalanceWeight()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
