package com.aiminilab.aitoolmarket.task.routing.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("account_model_route_state")
public class AccountModelRouteState {
    @TableId
    private Long id;
    private Long vendorAccountId;
    private Long modelConfigId;
    private Integer inFlightCount;
    private String circuitStatus;
    private Integer consecutiveFailures;
    private LocalDateTime cooldownUntil;
    private LocalDateTime lastSelectedAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVendorAccountId() { return vendorAccountId; }
    public void setVendorAccountId(Long vendorAccountId) { this.vendorAccountId = vendorAccountId; }
    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }
    public Integer getInFlightCount() { return inFlightCount; }
    public void setInFlightCount(Integer inFlightCount) { this.inFlightCount = inFlightCount; }
    public String getCircuitStatus() { return circuitStatus; }
    public void setCircuitStatus(String circuitStatus) { this.circuitStatus = circuitStatus; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public LocalDateTime getCooldownUntil() { return cooldownUntil; }
    public void setCooldownUntil(LocalDateTime cooldownUntil) { this.cooldownUntil = cooldownUntil; }
    public LocalDateTime getLastSelectedAt() { return lastSelectedAt; }
    public void setLastSelectedAt(LocalDateTime lastSelectedAt) { this.lastSelectedAt = lastSelectedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
