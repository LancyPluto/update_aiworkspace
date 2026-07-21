package com.aiminilab.aitoolmarket.task.routing;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.task.dto.RouteFailoverRequest;
import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRoutingPolicyTest {

    @Test
    void requiresTheSameExecutionAndPricingContract() {
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        AgentModelConfig reference = model(1L, 10L, "{\"quality\":\"hd\",\"n\":1}", "1.00");
        AgentModelConfig equivalent = model(2L, 20L, "{\"n\":1,\"quality\":\"hd\"}", "1.0");
        AgentModelConfig differentPrice = model(3L, 30L, "{\"quality\":\"hd\",\"n\":1}", "1.01");
        when(capabilityService.resolveCapabilities(reference)).thenReturn(List.of("IMAGE_GENERATION", "VISION_INPUT"));
        when(capabilityService.resolveCapabilities(equivalent)).thenReturn(List.of("vision_input", "image_generation"));
        when(capabilityService.resolveCapabilities(differentPrice)).thenReturn(List.of("IMAGE_GENERATION", "VISION_INPUT"));

        assertThat(ModelRoutingPolicy.compatible(reference, equivalent, capabilityService, new ObjectMapper()))
                .isTrue();
        assertThat(ModelRoutingPolicy.compatible(reference, differentPrice, capabilityService, new ObjectMapper()))
                .isFalse();
        assertThat(ModelRoutingPolicy.incompatibilityReason(
                reference, differentPrice, capabilityService, new ObjectMapper()))
                .isEqualTo("PRICE_MISMATCH");
    }

    @Test
    void choosesWeightedLeastInFlightAndUsesOldestSelectionAsTieBreaker() {
        LocalDateTime now = LocalDateTime.now();
        ModelRoutingPolicy.Candidate highWeight = candidate(1L, 10L, 100, 3, now.minusMinutes(1));
        ModelRoutingPolicy.Candidate lowWeight = candidate(2L, 20L, 20, 0, now.minusMinutes(10));

        assertThat(ModelRoutingPolicy.choose(List.of(lowWeight, highWeight), now))
                .isSameAs(highWeight);

        ModelRoutingPolicy.Candidate older = candidate(3L, 30L, 100, 0, now.minusMinutes(5));
        ModelRoutingPolicy.Candidate newer = candidate(4L, 40L, 100, 0, now.minusMinutes(1));
        assertThat(ModelRoutingPolicy.choose(List.of(newer, older), now)).isSameAs(older);
    }

    @Test
    void onlyFailsOverWhenDeliveryIsKnownSafe() {
        RouteFailoverRequest notSent = failure("NOT_SENT", null, false);
        RouteFailoverRequest rejected = failure("REJECTED", null, false);
        RouteFailoverRequest unknown = failure("UNKNOWN", null, false);

        assertThat(ModelRoutingPolicy.canFailover(notSent, false, null, false)).isTrue();
        assertThat(ModelRoutingPolicy.canFailover(rejected, false, null, false)).isTrue();
        assertThat(ModelRoutingPolicy.canFailover(unknown, false, null, false)).isFalse();
        assertThat(ModelRoutingPolicy.canFailover(notSent, true, null, false)).isFalse();
        assertThat(ModelRoutingPolicy.canFailover(notSent, false, "provider-task-1", false)).isFalse();
        assertThat(ModelRoutingPolicy.canFailover(failure("NOT_SENT", "provider-task-2", false), false, null, false))
                .isFalse();
        assertThat(ModelRoutingPolicy.canFailover(failure("NOT_SENT", null, true), false, null, false)).isFalse();
    }

    @Test
    void skipsAnOpenCircuitUntilCooldownExpires() {
        LocalDateTime now = LocalDateTime.now();
        ModelRoutingPolicy.Candidate open = candidate(1L, 10L, 100, 0, now.minusMinutes(2));
        open.state().setCircuitStatus("OPEN");
        open.state().setCooldownUntil(now.plusMinutes(1));
        ModelRoutingPolicy.Candidate closed = candidate(2L, 20L, 10, 5, now.minusMinutes(1));

        assertThat(ModelRoutingPolicy.choose(List.of(open, closed), now)).isSameAs(closed);
        open.state().setCooldownUntil(now.minusSeconds(1));
        assertThat(ModelRoutingPolicy.choose(List.of(open, closed), now)).isSameAs(open);

        open.state().setCircuitStatus("HALF_OPEN");
        open.state().setInFlightCount(1);
        assertThat(ModelRoutingPolicy.choose(List.of(open, closed), now)).isSameAs(closed);
    }

    private static AgentModelConfig model(Long id, Long accountId, String executionOptions, String unitPrice) {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(id);
        model.setVendorAccountId(accountId);
        model.setProvider("openai_images_gateway");
        model.setModelName("gpt-image-2");
        model.setExecutionTask("IMAGE_GENERATION");
        model.setExecutionOptionsJson(executionOptions);
        model.setBillingUnit("IMAGE");
        model.setUnitPrice(new BigDecimal(unitPrice));
        model.setEnabled(true);
        return model;
    }

    private static ModelRoutingPolicy.Candidate candidate(Long modelId,
                                                          Long accountId,
                                                          int weight,
                                                          int inFlight,
                                                          LocalDateTime lastSelectedAt) {
        AgentModelConfig model = model(modelId, accountId, null, "1.0");
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(accountId);
        account.setEnabled(true);
        account.setLoadBalanceEnabled(true);
        account.setLoadBalanceWeight(weight);
        AccountModelRouteState state = new AccountModelRouteState();
        state.setId(modelId);
        state.setModelConfigId(modelId);
        state.setVendorAccountId(accountId);
        state.setInFlightCount(inFlight);
        state.setCircuitStatus("CLOSED");
        state.setLastSelectedAt(lastSelectedAt);
        return new ModelRoutingPolicy.Candidate(model, account, state);
    }

    private static RouteFailoverRequest failure(String deliveryState,
                                                String providerRequestId,
                                                boolean providerCharged) {
        return new RouteFailoverRequest(
                "claim", 1L, deliveryState, "ACCOUNT", "SUBMIT",
                "MODEL_PROVIDER_UNAVAILABLE", "failed", null,
                providerRequestId, providerCharged, null
        );
    }
}
