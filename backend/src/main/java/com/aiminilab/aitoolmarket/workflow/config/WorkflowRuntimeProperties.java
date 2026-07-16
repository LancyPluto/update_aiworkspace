package com.aiminilab.aitoolmarket.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "workflow.runtime")
public class WorkflowRuntimeProperties {

    private boolean enabled;
    private boolean executionEnabled;
    private boolean realBillingEnabled;
    private boolean shadowBillingEnabled;
    private boolean autoRetryEnabled;
    private boolean confirmationEnabled;
    private List<Long> allowedUserIds = new ArrayList<>();
    private int canaryPercentage;
    private Duration attemptTimeout = Duration.ofMinutes(15);
    private Duration recoveryInterval = Duration.ofMinutes(1);
    private int recoveryBatchSize = 100;
    private Duration reconciliationInterval = Duration.ofMinutes(15);
    private int reconciliationBatchSize = 100;
    private int maxRunCostCredits;
    private int maxUserDailyCostCredits;
    private BigDecimal maxProviderDailyCostCny = BigDecimal.ZERO;
    private String costAlertWebhookUrl = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExecutionEnabled() {
        return executionEnabled;
    }

    public void setExecutionEnabled(boolean executionEnabled) {
        this.executionEnabled = executionEnabled;
    }

    public boolean isRealBillingEnabled() {
        return realBillingEnabled;
    }

    public void setRealBillingEnabled(boolean realBillingEnabled) {
        this.realBillingEnabled = realBillingEnabled;
    }

    public boolean isShadowBillingEnabled() {
        return shadowBillingEnabled;
    }

    public void setShadowBillingEnabled(boolean shadowBillingEnabled) {
        this.shadowBillingEnabled = shadowBillingEnabled;
    }

    public boolean isAutoRetryEnabled() {
        return autoRetryEnabled;
    }

    public void setAutoRetryEnabled(boolean autoRetryEnabled) {
        this.autoRetryEnabled = autoRetryEnabled;
    }

    public boolean isConfirmationEnabled() {
        return confirmationEnabled;
    }

    public void setConfirmationEnabled(boolean confirmationEnabled) {
        this.confirmationEnabled = confirmationEnabled;
    }

    public List<Long> getAllowedUserIds() {
        return List.copyOf(allowedUserIds);
    }

    public void setAllowedUserIds(List<Long> allowedUserIds) {
        this.allowedUserIds = allowedUserIds == null ? new ArrayList<>() : new ArrayList<>(allowedUserIds);
    }

    public int getCanaryPercentage() {
        return canaryPercentage;
    }

    public void setCanaryPercentage(int canaryPercentage) {
        this.canaryPercentage = canaryPercentage;
    }

    public Duration getAttemptTimeout() {
        return attemptTimeout;
    }

    public void setAttemptTimeout(Duration attemptTimeout) {
        this.attemptTimeout = attemptTimeout;
    }

    public Duration getRecoveryInterval() {
        return recoveryInterval;
    }

    public void setRecoveryInterval(Duration recoveryInterval) {
        this.recoveryInterval = recoveryInterval;
    }

    public int getRecoveryBatchSize() {
        return recoveryBatchSize;
    }

    public void setRecoveryBatchSize(int recoveryBatchSize) {
        this.recoveryBatchSize = recoveryBatchSize;
    }

    public Duration getReconciliationInterval() {
        return reconciliationInterval;
    }

    public void setReconciliationInterval(Duration reconciliationInterval) {
        this.reconciliationInterval = reconciliationInterval;
    }

    public int getReconciliationBatchSize() {
        return reconciliationBatchSize;
    }

    public void setReconciliationBatchSize(int reconciliationBatchSize) {
        this.reconciliationBatchSize = reconciliationBatchSize;
    }

    public int getMaxRunCostCredits() {
        return maxRunCostCredits;
    }

    public void setMaxRunCostCredits(int maxRunCostCredits) {
        this.maxRunCostCredits = maxRunCostCredits;
    }

    public int getMaxUserDailyCostCredits() {
        return maxUserDailyCostCredits;
    }

    public void setMaxUserDailyCostCredits(int maxUserDailyCostCredits) {
        this.maxUserDailyCostCredits = maxUserDailyCostCredits;
    }

    public BigDecimal getMaxProviderDailyCostCny() {
        return maxProviderDailyCostCny;
    }

    public void setMaxProviderDailyCostCny(BigDecimal maxProviderDailyCostCny) {
        this.maxProviderDailyCostCny = maxProviderDailyCostCny == null
                ? BigDecimal.ZERO
                : maxProviderDailyCostCny;
    }

    public String getCostAlertWebhookUrl() {
        return costAlertWebhookUrl;
    }

    public void setCostAlertWebhookUrl(String costAlertWebhookUrl) {
        this.costAlertWebhookUrl = costAlertWebhookUrl == null ? "" : costAlertWebhookUrl.trim();
    }
}
