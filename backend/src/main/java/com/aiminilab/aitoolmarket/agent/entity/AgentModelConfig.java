package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("agent_model_configs")
public class AgentModelConfig {
    @TableId
    private Long id;
    private String displayName;
    private String configCode;
    private String provider;
    private String modelName;
    private String baseUrl;
    private String apiKey;
    private String extraAuthJson;
    private String minimaxGroupId;
    private String consoleUrl;
    private String balanceUrl;
    private String docsUrl;
    private Integer timeoutSeconds;
    private BigDecimal inputTokenPricePer1k;
    private BigDecimal outputTokenPricePer1k;
    private BigDecimal inputTokenPricePer1m;
    private BigDecimal outputTokenPricePer1m;
    private String billingUnit;
    private BigDecimal unitPrice;
    private String capabilities;
    private Boolean enabled;
    private Boolean isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getConfigCode() { return configCode; }
    public void setConfigCode(String configCode) { this.configCode = configCode; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getExtraAuthJson() { return extraAuthJson; }
    public void setExtraAuthJson(String extraAuthJson) { this.extraAuthJson = extraAuthJson; }
    public String getMinimaxGroupId() { return minimaxGroupId; }
    public void setMinimaxGroupId(String minimaxGroupId) { this.minimaxGroupId = minimaxGroupId; }
    public String getConsoleUrl() { return consoleUrl; }
    public void setConsoleUrl(String consoleUrl) { this.consoleUrl = consoleUrl; }
    public String getBalanceUrl() { return balanceUrl; }
    public void setBalanceUrl(String balanceUrl) { this.balanceUrl = balanceUrl; }
    public String getDocsUrl() { return docsUrl; }
    public void setDocsUrl(String docsUrl) { this.docsUrl = docsUrl; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public BigDecimal getInputTokenPricePer1k() { return inputTokenPricePer1k; }
    public void setInputTokenPricePer1k(BigDecimal inputTokenPricePer1k) { this.inputTokenPricePer1k = inputTokenPricePer1k; }
    public BigDecimal getOutputTokenPricePer1k() { return outputTokenPricePer1k; }
    public void setOutputTokenPricePer1k(BigDecimal outputTokenPricePer1k) { this.outputTokenPricePer1k = outputTokenPricePer1k; }
    public BigDecimal getInputTokenPricePer1m() { return inputTokenPricePer1m; }
    public void setInputTokenPricePer1m(BigDecimal inputTokenPricePer1m) { this.inputTokenPricePer1m = inputTokenPricePer1m; }
    public BigDecimal getOutputTokenPricePer1m() { return outputTokenPricePer1m; }
    public void setOutputTokenPricePer1m(BigDecimal outputTokenPricePer1m) { this.outputTokenPricePer1m = outputTokenPricePer1m; }
    public String getBillingUnit() { return billingUnit; }
    public void setBillingUnit(String billingUnit) { this.billingUnit = billingUnit; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getDefault() { return isDefault; }
    public void setDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
