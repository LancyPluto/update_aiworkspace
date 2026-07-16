package com.aiminilab.aitoolmarket.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("workflow_confirmations")
public class WorkflowConfirmation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long stepId;
    private Long userId;
    private String tokenHash;
    private String parameterHash;
    private String allowedActionsJson;
    private String decision;
    private String feedbackJson;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getStepId() { return stepId; }
    public void setStepId(Long stepId) { this.stepId = stepId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getParameterHash() { return parameterHash; }
    public void setParameterHash(String parameterHash) { this.parameterHash = parameterHash; }
    public String getAllowedActionsJson() { return allowedActionsJson; }
    public void setAllowedActionsJson(String allowedActionsJson) { this.allowedActionsJson = allowedActionsJson; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getFeedbackJson() { return feedbackJson; }
    public void setFeedbackJson(String feedbackJson) { this.feedbackJson = feedbackJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
