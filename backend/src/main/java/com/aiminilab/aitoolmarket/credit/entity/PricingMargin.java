package com.aiminilab.aitoolmarket.credit.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Configurable platform markup + floor price applied on top of vendor cost.
 * Resolved by scope precedence MODEL &gt; CATEGORY &gt; GLOBAL.
 */
@TableName("pricing_margins")
public class PricingMargin {
    @TableId
    private Long id;
    private String scopeType;
    private Long scopeRef;
    private BigDecimal markupRatio;
    private Integer minCredits;
    private Integer imageEstimateInputTokens;
    private Integer imageEstimateOutputTokens;
    private Boolean enabled;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeRef() { return scopeRef; }
    public void setScopeRef(Long scopeRef) { this.scopeRef = scopeRef; }
    public BigDecimal getMarkupRatio() { return markupRatio; }
    public void setMarkupRatio(BigDecimal markupRatio) { this.markupRatio = markupRatio; }
    public Integer getMinCredits() { return minCredits; }
    public void setMinCredits(Integer minCredits) { this.minCredits = minCredits; }
    public Integer getImageEstimateInputTokens() { return imageEstimateInputTokens; }
    public void setImageEstimateInputTokens(Integer imageEstimateInputTokens) { this.imageEstimateInputTokens = imageEstimateInputTokens; }
    public Integer getImageEstimateOutputTokens() { return imageEstimateOutputTokens; }
    public void setImageEstimateOutputTokens(Integer imageEstimateOutputTokens) { this.imageEstimateOutputTokens = imageEstimateOutputTokens; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
