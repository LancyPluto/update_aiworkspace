package com.aiminilab.aitoolmarket.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("workflow_runs")
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long toolId;
    private Long workflowId;
    private Integer workflowVersion;
    private Long workflowVersionId;
    private Long rootTaskId;
    private String launchSource;
    private String clientRequestId;
    private String status;
    private Long revision;
    private Long cancellationGeneration;
    private String inputJson;
    private String contextJson;
    private String currentNodeId;
    private Long currentStepId;
    private String errorCode;
    private String userMessage;
    private String developerMessage;
    private String failureTraceId;
    private String billingStatus;
    private BigDecimal providerCostReservedCny;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
    public Integer getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(Integer workflowVersion) { this.workflowVersion = workflowVersion; }
    public Long getWorkflowVersionId() { return workflowVersionId; }
    public void setWorkflowVersionId(Long workflowVersionId) { this.workflowVersionId = workflowVersionId; }
    public Long getRootTaskId() { return rootTaskId; }
    public void setRootTaskId(Long rootTaskId) { this.rootTaskId = rootTaskId; }
    public String getLaunchSource() { return launchSource; }
    public void setLaunchSource(String launchSource) { this.launchSource = launchSource; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public Long getCancellationGeneration() { return cancellationGeneration; }
    public void setCancellationGeneration(Long cancellationGeneration) { this.cancellationGeneration = cancellationGeneration; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }
    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }
    public Long getCurrentStepId() { return currentStepId; }
    public void setCurrentStepId(Long currentStepId) { this.currentStepId = currentStepId; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getDeveloperMessage() { return developerMessage; }
    public void setDeveloperMessage(String developerMessage) { this.developerMessage = developerMessage; }
    public String getFailureTraceId() { return failureTraceId; }
    public void setFailureTraceId(String failureTraceId) { this.failureTraceId = failureTraceId; }
    public String getBillingStatus() { return billingStatus; }
    public void setBillingStatus(String billingStatus) { this.billingStatus = billingStatus; }
    public BigDecimal getProviderCostReservedCny() { return providerCostReservedCny; }
    public void setProviderCostReservedCny(BigDecimal providerCostReservedCny) { this.providerCostReservedCny = providerCostReservedCny; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
