package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimePropertiesTest {

    @Test
    void defaultsAreConservative() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isExecutionEnabled()).isFalse();
        assertThat(properties.isRealBillingEnabled()).isFalse();
        assertThat(properties.isShadowBillingEnabled()).isFalse();
        assertThat(properties.isAutoRetryEnabled()).isFalse();
        assertThat(properties.isConfirmationEnabled()).isFalse();
        assertThat(properties.getAttemptTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getRecoveryInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getRecoveryBatchSize()).isEqualTo(100);
        assertThat(properties.getReconciliationInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getReconciliationBatchSize()).isEqualTo(100);
    }

    @Test
    void bindsRuntimeControls() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("workflow.runtime.enabled", "true"),
                Map.entry("workflow.runtime.execution-enabled", "true"),
                Map.entry("workflow.runtime.real-billing-enabled", "true"),
                Map.entry("workflow.runtime.shadow-billing-enabled", "false"),
                Map.entry("workflow.runtime.auto-retry-enabled", "true"),
                Map.entry("workflow.runtime.confirmation-enabled", "true"),
                Map.entry("workflow.runtime.attempt-timeout", "PT20M"),
                Map.entry("workflow.runtime.recovery-interval", "PT2M"),
                Map.entry("workflow.runtime.recovery-batch-size", "40"),
                Map.entry("workflow.runtime.reconciliation-interval", "PT6H"),
                Map.entry("workflow.runtime.reconciliation-batch-size", "50")
        ));

        WorkflowRuntimeProperties properties = new Binder(source)
                .bind("workflow.runtime", Bindable.of(WorkflowRuntimeProperties.class))
                .orElseThrow(() -> new AssertionError("workflow.runtime did not bind"));

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isExecutionEnabled()).isTrue();
        assertThat(properties.isRealBillingEnabled()).isTrue();
        assertThat(properties.isAutoRetryEnabled()).isTrue();
        assertThat(properties.isConfirmationEnabled()).isTrue();
        assertThat(properties.getAttemptTimeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(properties.getRecoveryInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getRecoveryBatchSize()).isEqualTo(40);
        assertThat(properties.getReconciliationInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.getReconciliationBatchSize()).isEqualTo(50);
    }
}
