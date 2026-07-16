package com.aiminilab.aitoolmarket.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("workflow_step_charges")
public class WorkflowStepCharge {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long stepId;
    private Long attemptId;
    private Long userId;
    private String status;
    private Integer reservedCredits;
    private Integer chargedCredits;
    private BigDecimal providerCost;
    private String providerCostCurrency;
    private String idempotencyKey;
    private Long creditLogId;
    private Long billingUsageId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getStepId() { return stepId; }
    public void setStepId(Long stepId) { this.stepId = stepId; }
    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getReservedCredits() { return reservedCredits; }
    public void setReservedCredits(Integer reservedCredits) { this.reservedCredits = reservedCredits; }
    public Integer getChargedCredits() { return chargedCredits; }
    public void setChargedCredits(Integer chargedCredits) { this.chargedCredits = chargedCredits; }
    public BigDecimal getProviderCost() { return providerCost; }
    public void setProviderCost(BigDecimal providerCost) { this.providerCost = providerCost; }
    public String getProviderCostCurrency() { return providerCostCurrency; }
    public void setProviderCostCurrency(String providerCostCurrency) { this.providerCostCurrency = providerCostCurrency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Long getCreditLogId() { return creditLogId; }
    public void setCreditLogId(Long creditLogId) { this.creditLogId = creditLogId; }
    public Long getBillingUsageId() { return billingUsageId; }
    public void setBillingUsageId(Long billingUsageId) { this.billingUsageId = billingUsageId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
