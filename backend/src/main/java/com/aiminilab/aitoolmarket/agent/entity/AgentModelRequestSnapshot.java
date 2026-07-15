package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_model_request_snapshots")
public class AgentModelRequestSnapshot {
    @TableId private Long id;
    private Long runId;
    private Long userId;
    private Integer requestSequence;
    private String requestStage;
    private Integer iterationNo;
    private String modelProviderCode;
    private String modelName;
    private Integer messageCount;
    private Integer toolCount;
    private Integer estimatedInputTokens;
    private String skillCodesJson;
    private String payloadJson;
    private String payloadSha256;
    private LocalDateTime payloadExpiresAt;
    private LocalDateTime payloadExpiredAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getRequestSequence() { return requestSequence; }
    public void setRequestSequence(Integer requestSequence) { this.requestSequence = requestSequence; }
    public String getRequestStage() { return requestStage; }
    public void setRequestStage(String requestStage) { this.requestStage = requestStage; }
    public Integer getIterationNo() { return iterationNo; }
    public void setIterationNo(Integer iterationNo) { this.iterationNo = iterationNo; }
    public String getModelProviderCode() { return modelProviderCode; }
    public void setModelProviderCode(String modelProviderCode) { this.modelProviderCode = modelProviderCode; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public Integer getToolCount() { return toolCount; }
    public void setToolCount(Integer toolCount) { this.toolCount = toolCount; }
    public Integer getEstimatedInputTokens() { return estimatedInputTokens; }
    public void setEstimatedInputTokens(Integer estimatedInputTokens) { this.estimatedInputTokens = estimatedInputTokens; }
    public String getSkillCodesJson() { return skillCodesJson; }
    public void setSkillCodesJson(String skillCodesJson) { this.skillCodesJson = skillCodesJson; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(String payloadSha256) { this.payloadSha256 = payloadSha256; }
    public LocalDateTime getPayloadExpiresAt() { return payloadExpiresAt; }
    public void setPayloadExpiresAt(LocalDateTime payloadExpiresAt) { this.payloadExpiresAt = payloadExpiresAt; }
    public LocalDateTime getPayloadExpiredAt() { return payloadExpiredAt; }
    public void setPayloadExpiredAt(LocalDateTime payloadExpiredAt) { this.payloadExpiredAt = payloadExpiredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
