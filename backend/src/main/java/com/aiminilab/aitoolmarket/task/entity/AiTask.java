package com.aiminilab.aitoolmarket.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_tasks")
public class AiTask {
    @TableId
    private Long id;
    private String taskNo;
    private Long userId;
    private Long toolId;
    private Long modelConfigId;
    private Long selectedModelConfigId;
    private Long selectedVendorAccountId;
    private Long currentRouteAttemptId;
    @TableField(exist = false)
    private String modelConfigName;
    @TableField(exist = false)
    private String modelName;
    @TableField(exist = false)
    private String toolCode;
    @TableField(exist = false)
    private String toolName;
    @TableField(exist = false)
    private String toolType;
    @TableField(exist = false)
    private String inputModality;
    @TableField(exist = false)
    private String outputModality;
    @TableField(exist = false)
    private String executionHandler;
    private String status;
    private Integer progress;
    private String progressMessage;
    private String paramsJson;
    private String modelSnapshotJson;
    private String idempotencyKey;
    private Integer estimatedCreditCost;
    private String errorCode;
    private String errorMessage;
    private String userMessage;
    private String developerMessage;
    private String failureTraceId;
    private String providerErrorCode;
    private String providerRequestId;
    private String claimedBy;
    private String claimToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime claimedAt;
    private LocalDateTime leaseRenewedAt;
    private Integer executionAttempt;
    private String providerCheckpointJson;
    private Integer providerCheckpointVersion;
    private LocalDateTime createdAt;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskNo() {
        return taskNo;
    }

    public void setTaskNo(String taskNo) {
        this.taskNo = taskNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getToolId() {
        return toolId;
    }

    public void setToolId(Long toolId) {
        this.toolId = toolId;
    }

    public Long getModelConfigId() {
        return modelConfigId;
    }

    public void setModelConfigId(Long modelConfigId) {
        this.modelConfigId = modelConfigId;
    }

    public Long getSelectedModelConfigId() {
        return selectedModelConfigId;
    }

    public void setSelectedModelConfigId(Long selectedModelConfigId) {
        this.selectedModelConfigId = selectedModelConfigId;
    }

    public Long getSelectedVendorAccountId() {
        return selectedVendorAccountId;
    }

    public void setSelectedVendorAccountId(Long selectedVendorAccountId) {
        this.selectedVendorAccountId = selectedVendorAccountId;
    }

    public Long getCurrentRouteAttemptId() {
        return currentRouteAttemptId;
    }

    public void setCurrentRouteAttemptId(Long currentRouteAttemptId) {
        this.currentRouteAttemptId = currentRouteAttemptId;
    }

    public String getModelConfigName() {
        return modelConfigName;
    }

    public void setModelConfigName(String modelConfigName) {
        this.modelConfigName = modelConfigName;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getToolCode() {
        return toolCode;
    }

    public void setToolCode(String toolCode) {
        this.toolCode = toolCode;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public String getInputModality() {
        return inputModality;
    }

    public void setInputModality(String inputModality) {
        this.inputModality = inputModality;
    }

    public String getOutputModality() {
        return outputModality;
    }

    public void setOutputModality(String outputModality) {
        this.outputModality = outputModality;
    }

    public String getExecutionHandler() {
        return executionHandler;
    }

    public void setExecutionHandler(String executionHandler) {
        this.executionHandler = executionHandler;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public String getProgressMessage() {
        return progressMessage;
    }

    public void setProgressMessage(String progressMessage) {
        this.progressMessage = progressMessage;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }

    public String getModelSnapshotJson() {
        return modelSnapshotJson;
    }

    public void setModelSnapshotJson(String modelSnapshotJson) {
        this.modelSnapshotJson = modelSnapshotJson;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Integer getEstimatedCreditCost() {
        return estimatedCreditCost;
    }

    public void setEstimatedCreditCost(Integer estimatedCreditCost) {
        this.estimatedCreditCost = estimatedCreditCost;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getDeveloperMessage() {
        return developerMessage;
    }

    public void setDeveloperMessage(String developerMessage) {
        this.developerMessage = developerMessage;
    }

    public String getFailureTraceId() {
        return failureTraceId;
    }

    public void setFailureTraceId(String failureTraceId) {
        this.failureTraceId = failureTraceId;
    }

    public String getProviderErrorCode() {
        return providerErrorCode;
    }

    public void setProviderErrorCode(String providerErrorCode) {
        this.providerErrorCode = providerErrorCode;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(LocalDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(LocalDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public LocalDateTime getLeaseRenewedAt() {
        return leaseRenewedAt;
    }

    public void setLeaseRenewedAt(LocalDateTime leaseRenewedAt) {
        this.leaseRenewedAt = leaseRenewedAt;
    }

    public Integer getExecutionAttempt() {
        return executionAttempt;
    }

    public void setExecutionAttempt(Integer executionAttempt) {
        this.executionAttempt = executionAttempt;
    }

    public String getProviderCheckpointJson() {
        return providerCheckpointJson;
    }

    public void setProviderCheckpointJson(String providerCheckpointJson) {
        this.providerCheckpointJson = providerCheckpointJson;
    }

    public Integer getProviderCheckpointVersion() {
        return providerCheckpointVersion;
    }

    public void setProviderCheckpointVersion(Integer providerCheckpointVersion) {
        this.providerCheckpointVersion = providerCheckpointVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(LocalDateTime queuedAt) {
        this.queuedAt = queuedAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
