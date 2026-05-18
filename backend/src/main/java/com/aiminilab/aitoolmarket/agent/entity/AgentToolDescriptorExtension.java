package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_tool_descriptor_extension")
public class AgentToolDescriptorExtension {
    @TableId
    private Long id;
    private Long toolId;
    private String toolCode;
    private Boolean agentEnabled;
    private Boolean agentRecommendable;
    private Boolean agentAutoCallable;
    private String confirmationPolicy;
    private String riskLevel;
    private String keywordsJson;
    private String examplePromptsJson;
    private String applicableScenariosJson;
    private String notApplicableScenariosJson;
    private String resultSchemaJson;
    private String outputType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }
    public String getToolCode() { return toolCode; }
    public void setToolCode(String toolCode) { this.toolCode = toolCode; }
    public Boolean getAgentEnabled() { return agentEnabled; }
    public void setAgentEnabled(Boolean agentEnabled) { this.agentEnabled = agentEnabled; }
    public Boolean getAgentRecommendable() { return agentRecommendable; }
    public void setAgentRecommendable(Boolean agentRecommendable) { this.agentRecommendable = agentRecommendable; }
    public Boolean getAgentAutoCallable() { return agentAutoCallable; }
    public void setAgentAutoCallable(Boolean agentAutoCallable) { this.agentAutoCallable = agentAutoCallable; }
    public String getConfirmationPolicy() { return confirmationPolicy; }
    public void setConfirmationPolicy(String confirmationPolicy) { this.confirmationPolicy = confirmationPolicy; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getKeywordsJson() { return keywordsJson; }
    public void setKeywordsJson(String keywordsJson) { this.keywordsJson = keywordsJson; }
    public String getExamplePromptsJson() { return examplePromptsJson; }
    public void setExamplePromptsJson(String examplePromptsJson) { this.examplePromptsJson = examplePromptsJson; }
    public String getApplicableScenariosJson() { return applicableScenariosJson; }
    public void setApplicableScenariosJson(String applicableScenariosJson) { this.applicableScenariosJson = applicableScenariosJson; }
    public String getNotApplicableScenariosJson() { return notApplicableScenariosJson; }
    public void setNotApplicableScenariosJson(String notApplicableScenariosJson) { this.notApplicableScenariosJson = notApplicableScenariosJson; }
    public String getResultSchemaJson() { return resultSchemaJson; }
    public void setResultSchemaJson(String resultSchemaJson) { this.resultSchemaJson = resultSchemaJson; }
    public String getOutputType() { return outputType; }
    public void setOutputType(String outputType) { this.outputType = outputType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
