package com.aiminilab.aitoolmarket.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_context_snapshots")
public class AgentContextSnapshot {
    @TableId
    private Long id;
    private Long runId;
    private Long sessionId;
    private Long userId;
    private Long workspaceId;
    private Long modelConfigId;
    private String modelProviderCode;
    private String modelName;
    private String strategy;
    private Integer maxHistoryMessages;
    private Integer historyMessageCount;
    private Integer fileCount;
    private Integer fileChunkCount;
    private Integer memoryItemCount;
    private Integer estimatedInputTokens;
    private String snapshotJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getModelProviderCode() { return modelProviderCode; }
    public void setModelProviderCode(String modelProviderCode) { this.modelProviderCode = modelProviderCode; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public Integer getMaxHistoryMessages() { return maxHistoryMessages; }
    public void setMaxHistoryMessages(Integer maxHistoryMessages) { this.maxHistoryMessages = maxHistoryMessages; }
    public Integer getHistoryMessageCount() { return historyMessageCount; }
    public void setHistoryMessageCount(Integer historyMessageCount) { this.historyMessageCount = historyMessageCount; }
    public Integer getFileCount() { return fileCount; }
    public void setFileCount(Integer fileCount) { this.fileCount = fileCount; }
    public Integer getFileChunkCount() { return fileChunkCount; }
    public void setFileChunkCount(Integer fileChunkCount) { this.fileChunkCount = fileChunkCount; }
    public Integer getMemoryItemCount() { return memoryItemCount; }
    public void setMemoryItemCount(Integer memoryItemCount) { this.memoryItemCount = memoryItemCount; }
    public Integer getEstimatedInputTokens() { return estimatedInputTokens; }
    public void setEstimatedInputTokens(Integer estimatedInputTokens) { this.estimatedInputTokens = estimatedInputTokens; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
