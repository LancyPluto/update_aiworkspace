package com.aiminilab.aitoolmarket.admin.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class BillingMetrics {

    private final MeterRegistry meterRegistry;

    public BillingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordUsage(String sourceType, String outcome, String provider, String billingUnit,
                            BigDecimal vendorCostAmount, int customerChargedCredits,
                            String failureStage, String errorCode, String modelName, boolean providerCharged) {
        Tags usageTags = Tags.of(
                "source_type", normalize(sourceType, "UNKNOWN"),
                "outcome", normalize(outcome, "UNKNOWN"),
                "provider", normalize(provider, "UNKNOWN"),
                "billing_unit", normalize(billingUnit, "UNKNOWN")
        );
        meterRegistry.counter("ai_billing_usage_total", usageTags).increment();
        double vendorCost = vendorCostAmount == null ? 0 : vendorCostAmount.max(BigDecimal.ZERO).doubleValue();
        if (vendorCost > 0) {
            meterRegistry.counter(
                    "ai_billing_vendor_cost_cny_total",
                    "source_type", normalize(sourceType, "UNKNOWN"),
                    "outcome", normalize(outcome, "UNKNOWN"),
                    "provider", normalize(provider, "UNKNOWN"),
                    "failure_stage", normalize(failureStage, "none"),
                    "error_code", normalize(errorCode, "none")
            ).increment(vendorCost);
        }
        if (customerChargedCredits > 0) {
            meterRegistry.counter(
                    "ai_billing_customer_charged_credits_total",
                    "source_type", normalize(sourceType, "UNKNOWN"),
                    "outcome", normalize(outcome, "UNKNOWN"),
                    "provider", normalize(provider, "UNKNOWN")
            ).increment(customerChargedCredits);
        }
        if (!"SUCCESS".equalsIgnoreCase(normalize(outcome, "UNKNOWN"))) {
            meterRegistry.counter(
                    "ai_provider_failure_total",
                    "provider", normalize(provider, "UNKNOWN"),
                    "model_name", normalize(modelName, "UNKNOWN"),
                    "error_code", normalize(errorCode, "UNKNOWN"),
                    "failure_stage", normalize(failureStage, "UNKNOWN"),
                    "provider_charged", providerCharged ? "true" : "false"
            ).increment();
        }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
