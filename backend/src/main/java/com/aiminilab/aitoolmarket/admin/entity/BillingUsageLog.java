package com.aiminilab.aitoolmarket.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("billing_usage_logs")
public class BillingUsageLog {
    @TableId
    private Long id;
    private String sourceType;
    private Long sourceId;
    private Long userId;
    private Long modelConfigId;
    private String provider;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    @TableField("input_token_price_per_1k")
    private BigDecimal inputTokenPricePer1k;
    @TableField("output_token_price_per_1k")
    private BigDecimal outputTokenPricePer1k;
    @TableField("input_token_price_per_1m")
    private BigDecimal inputTokenPricePer1m;
    @TableField("output_token_price_per_1m")
    private BigDecimal outputTokenPricePer1m;
    private String billingUnit;
    private Integer billableUnits;
    private BigDecimal unitPrice;
    private BigDecimal costAmount;
    private BigDecimal vendorCostAmount;
    private Integer chargedCredits;
    private Integer customerChargeCredits;
    private Integer marginCredits;
    private BigDecimal markupRatio;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
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
    public Integer getBillableUnits() { return billableUnits; }
    public void setBillableUnits(Integer billableUnits) { this.billableUnits = billableUnits; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }
    public BigDecimal getVendorCostAmount() { return vendorCostAmount; }
    public void setVendorCostAmount(BigDecimal vendorCostAmount) { this.vendorCostAmount = vendorCostAmount; }
    public Integer getChargedCredits() { return chargedCredits; }
    public void setChargedCredits(Integer chargedCredits) { this.chargedCredits = chargedCredits; }
    public Integer getCustomerChargeCredits() { return customerChargeCredits; }
    public void setCustomerChargeCredits(Integer customerChargeCredits) { this.customerChargeCredits = customerChargeCredits; }
    public Integer getMarginCredits() { return marginCredits; }
    public void setMarginCredits(Integer marginCredits) { this.marginCredits = marginCredits; }
    public BigDecimal getMarkupRatio() { return markupRatio; }
    public void setMarkupRatio(BigDecimal markupRatio) { this.markupRatio = markupRatio; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
