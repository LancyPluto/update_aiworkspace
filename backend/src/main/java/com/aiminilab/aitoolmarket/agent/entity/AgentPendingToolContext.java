package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_pending_tool_context")
public class AgentPendingToolContext {
    @TableId
    private Long id;
    private Long runId;
    private Long sessionId;
    private Long userId;
    private String selectedToolCode;
    private String candidateToolCodesJson;
    private String collectedArgumentsJson;
    private String missingArgumentsJson;
    private String clarifyingQuestion;
    private Boolean confirmationRequired;
    private String source;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSelectedToolCode() { return selectedToolCode; }
    public void setSelectedToolCode(String selectedToolCode) { this.selectedToolCode = selectedToolCode; }
    public String getCandidateToolCodesJson() { return candidateToolCodesJson; }
    public void setCandidateToolCodesJson(String candidateToolCodesJson) { this.candidateToolCodesJson = candidateToolCodesJson; }
    public String getCollectedArgumentsJson() { return collectedArgumentsJson; }
    public void setCollectedArgumentsJson(String collectedArgumentsJson) { this.collectedArgumentsJson = collectedArgumentsJson; }
    public String getMissingArgumentsJson() { return missingArgumentsJson; }
    public void setMissingArgumentsJson(String missingArgumentsJson) { this.missingArgumentsJson = missingArgumentsJson; }
    public String getClarifyingQuestion() { return clarifyingQuestion; }
    public void setClarifyingQuestion(String clarifyingQuestion) { this.clarifyingQuestion = clarifyingQuestion; }
    public Boolean getConfirmationRequired() { return confirmationRequired; }
    public void setConfirmationRequired(Boolean confirmationRequired) { this.confirmationRequired = confirmationRequired; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
