package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("model_vendor_accounts")
public class ModelVendorAccount {
    @TableId
    private Long id;
    private String vendorCode;
    private String accountName;
    private String baseUrl;
    private String apiKey;
    private String extraAuthJson;
    private String consoleUrl;
    private String balanceUrl;
    private String balanceQueryMode;
    private BigDecimal balanceAmount;
    private String balanceCurrency;
    private String balanceStatus;
    private BigDecimal balanceLowThreshold;
    private LocalDateTime balanceUpdatedAt;
    private String balanceErrorMessage;
    private String healthStatus;
    private Boolean enabled;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVendorCode() { return vendorCode; }
    public void setVendorCode(String vendorCode) { this.vendorCode = vendorCode; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getExtraAuthJson() { return extraAuthJson; }
    public void setExtraAuthJson(String extraAuthJson) { this.extraAuthJson = extraAuthJson; }
    public String getConsoleUrl() { return consoleUrl; }
    public void setConsoleUrl(String consoleUrl) { this.consoleUrl = consoleUrl; }
    public String getBalanceUrl() { return balanceUrl; }
    public void setBalanceUrl(String balanceUrl) { this.balanceUrl = balanceUrl; }
    public String getBalanceQueryMode() { return balanceQueryMode; }
    public void setBalanceQueryMode(String balanceQueryMode) { this.balanceQueryMode = balanceQueryMode; }
    public BigDecimal getBalanceAmount() { return balanceAmount; }
    public void setBalanceAmount(BigDecimal balanceAmount) { this.balanceAmount = balanceAmount; }
    public String getBalanceCurrency() { return balanceCurrency; }
    public void setBalanceCurrency(String balanceCurrency) { this.balanceCurrency = balanceCurrency; }
    public String getBalanceStatus() { return balanceStatus; }
    public void setBalanceStatus(String balanceStatus) { this.balanceStatus = balanceStatus; }
    public BigDecimal getBalanceLowThreshold() { return balanceLowThreshold; }
    public void setBalanceLowThreshold(BigDecimal balanceLowThreshold) { this.balanceLowThreshold = balanceLowThreshold; }
    public LocalDateTime getBalanceUpdatedAt() { return balanceUpdatedAt; }
    public void setBalanceUpdatedAt(LocalDateTime balanceUpdatedAt) { this.balanceUpdatedAt = balanceUpdatedAt; }
    public String getBalanceErrorMessage() { return balanceErrorMessage; }
    public void setBalanceErrorMessage(String balanceErrorMessage) { this.balanceErrorMessage = balanceErrorMessage; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
