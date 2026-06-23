package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_skill_bundles")
public class AgentSkillBundle {
    @TableId
    private Long id;
    private String skillCode;
    private String displayName;
    private String description;
    private String toolCodesJson;
    private String sopRules;
    private String whenToUse;
    private String whenNotToUse;
    private String fieldPolicyJson;
    private String examplesJson;
    private String status;
    private Integer version;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSkillCode() { return skillCode; }
    public void setSkillCode(String skillCode) { this.skillCode = skillCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getToolCodesJson() { return toolCodesJson; }
    public void setToolCodesJson(String toolCodesJson) { this.toolCodesJson = toolCodesJson; }
    public String getSopRules() { return sopRules; }
    public void setSopRules(String sopRules) { this.sopRules = sopRules; }
    public String getWhenToUse() { return whenToUse; }
    public void setWhenToUse(String whenToUse) { this.whenToUse = whenToUse; }
    public String getWhenNotToUse() { return whenNotToUse; }
    public void setWhenNotToUse(String whenNotToUse) { this.whenNotToUse = whenNotToUse; }
    public String getFieldPolicyJson() { return fieldPolicyJson; }
    public void setFieldPolicyJson(String fieldPolicyJson) { this.fieldPolicyJson = fieldPolicyJson; }
    public String getExamplesJson() { return examplesJson; }
    public void setExamplesJson(String examplesJson) { this.examplesJson = examplesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
