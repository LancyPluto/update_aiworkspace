package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
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
    private String consoleCookie;
    private String consoleCookieStatus;
    private String balanceQueryMode;
    private BigDecimal balanceAmount;
    private String balanceCurrency;
    private String balanceStatus;
    private BigDecimal balanceLowThreshold;
    private LocalDateTime balanceUpdatedAt;
    private String balanceErrorMessage;
    private String healthStatus;
    private String healthMessage;
    private LocalDateTime healthCheckedAt;
    private Boolean loadBalanceEnabled;
    private Integer loadBalanceWeight;
    private Long routingPoolId;
    private Boolean enabled;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(exist = false)
    private Integer routingInFlightCount;
    @TableField(exist = false)
    private String routingCircuitStatus;
    @TableField(exist = false)
    private LocalDateTime routingCooldownUntil;
    @TableField(exist = false)
    private String routingPoolName;

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
    public String getConsoleCookie() { return consoleCookie; }
    public void setConsoleCookie(String consoleCookie) { this.consoleCookie = consoleCookie; }
    public String getConsoleCookieStatus() { return consoleCookieStatus; }
    public void setConsoleCookieStatus(String consoleCookieStatus) { this.consoleCookieStatus = consoleCookieStatus; }
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
    public String getHealthMessage() { return healthMessage; }
    public void setHealthMessage(String healthMessage) { this.healthMessage = healthMessage; }
    public LocalDateTime getHealthCheckedAt() { return healthCheckedAt; }
    public void setHealthCheckedAt(LocalDateTime healthCheckedAt) { this.healthCheckedAt = healthCheckedAt; }
    public Boolean getLoadBalanceEnabled() { return loadBalanceEnabled; }
    public void setLoadBalanceEnabled(Boolean loadBalanceEnabled) { this.loadBalanceEnabled = loadBalanceEnabled; }
    public Integer getLoadBalanceWeight() { return loadBalanceWeight; }
    public void setLoadBalanceWeight(Integer loadBalanceWeight) { this.loadBalanceWeight = loadBalanceWeight; }
    public Long getRoutingPoolId() { return routingPoolId; }
    public void setRoutingPoolId(Long routingPoolId) { this.routingPoolId = routingPoolId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getRoutingInFlightCount() { return routingInFlightCount; }
    public void setRoutingInFlightCount(Integer routingInFlightCount) { this.routingInFlightCount = routingInFlightCount; }
    public String getRoutingCircuitStatus() { return routingCircuitStatus; }
    public void setRoutingCircuitStatus(String routingCircuitStatus) { this.routingCircuitStatus = routingCircuitStatus; }
    public LocalDateTime getRoutingCooldownUntil() { return routingCooldownUntil; }
    public void setRoutingCooldownUntil(LocalDateTime routingCooldownUntil) { this.routingCooldownUntil = routingCooldownUntil; }
    public String getRoutingPoolName() { return routingPoolName; }
    public void setRoutingPoolName(String routingPoolName) { this.routingPoolName = routingPoolName; }
}
