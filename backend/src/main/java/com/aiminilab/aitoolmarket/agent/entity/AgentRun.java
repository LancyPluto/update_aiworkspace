package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_runs")
public class AgentRun {
    @TableId
    private Long id;
    private Long sessionId;
    private Long userId;
    private String status;
    private String intent;
    private String modelProviderCode;
    private String modelName;
    private Integer estimatedCredits;
    private Integer consumedCredits;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long parentRunId;
    private Long sourceUserMessageId;
    private String clientRequestId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getModelProviderCode() { return modelProviderCode; }
    public void setModelProviderCode(String modelProviderCode) { this.modelProviderCode = modelProviderCode; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Integer getEstimatedCredits() { return estimatedCredits; }
    public void setEstimatedCredits(Integer estimatedCredits) { this.estimatedCredits = estimatedCredits; }
    public Integer getConsumedCredits() { return consumedCredits; }
    public void setConsumedCredits(Integer consumedCredits) { this.consumedCredits = consumedCredits; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getParentRunId() { return parentRunId; }
    public void setParentRunId(Long parentRunId) { this.parentRunId = parentRunId; }
    public Long getSourceUserMessageId() { return sourceUserMessageId; }
    public void setSourceUserMessageId(Long sourceUserMessageId) { this.sourceUserMessageId = sourceUserMessageId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
}
