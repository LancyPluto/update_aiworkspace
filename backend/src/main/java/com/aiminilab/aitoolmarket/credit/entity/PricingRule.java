package com.aiminilab.aitoolmarket.credit.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Parameter-driven pricing adjustment (multiplier / tier / additive) applied to the
 * vendor cost before markup. Matched by scope (MODEL/TOOL/CATEGORY) and a param condition.
 */
@TableName("pricing_rules")
public class PricingRule {
    @TableId
    private Long id;
    private String scopeType;
    private Long scopeRef;
    private String paramKey;
    private String ruleType;
    private String matchOp;
    private String matchValue;
    private BigDecimal factor;
    private Integer extraCredits;
    private Integer priority;
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
    public String getParamKey() { return paramKey; }
    public void setParamKey(String paramKey) { this.paramKey = paramKey; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getMatchOp() { return matchOp; }
    public void setMatchOp(String matchOp) { this.matchOp = matchOp; }
    public String getMatchValue() { return matchValue; }
    public void setMatchValue(String matchValue) { this.matchValue = matchValue; }
    public BigDecimal getFactor() { return factor; }
    public void setFactor(BigDecimal factor) { this.factor = factor; }
    public Integer getExtraCredits() { return extraCredits; }
    public void setExtraCredits(Integer extraCredits) { this.extraCredits = extraCredits; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
