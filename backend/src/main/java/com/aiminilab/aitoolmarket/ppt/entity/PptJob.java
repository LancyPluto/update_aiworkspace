package com.aiminilab.aitoolmarket.ppt.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ppt_jobs")
public class PptJob {
    @TableId
    private Long id;
    private Long userId;
    private Long projectId;
    private Long rootJobId;
    private String jobType;
    private String status;
    private String engineCode;
    private String externalJobId;
    private String idempotencyKey;
    private Integer attemptNo;
    private Integer progress;
    private String progressMessage;
    private String requestJson;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private Boolean retryable;
    private Integer reservedCredits;
    private Integer actualCredits;
    private String creditState;
    private LocalDateTime nextPollAt;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime submissionStartedAt;
    private LocalDateTime reconcileStartedAt;
    private LocalDateTime lastEngineHeartbeatAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getRootJobId() { return rootJobId; }
    public void setRootJobId(Long rootJobId) { this.rootJobId = rootJobId; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEngineCode() { return engineCode; }
    public void setEngineCode(String engineCode) { this.engineCode = engineCode; }
    public String getExternalJobId() { return externalJobId; }
    public void setExternalJobId(String externalJobId) { this.externalJobId = externalJobId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getProgressMessage() { return progressMessage; }
    public void setProgressMessage(String progressMessage) { this.progressMessage = progressMessage; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Boolean getRetryable() { return retryable; }
    public void setRetryable(Boolean retryable) { this.retryable = retryable; }
    public Integer getReservedCredits() { return reservedCredits; }
    public void setReservedCredits(Integer reservedCredits) { this.reservedCredits = reservedCredits; }
    public Integer getActualCredits() { return actualCredits; }
    public void setActualCredits(Integer actualCredits) { this.actualCredits = actualCredits; }
    public String getCreditState() { return creditState; }
    public void setCreditState(String creditState) { this.creditState = creditState; }
    public LocalDateTime getNextPollAt() { return nextPollAt; }
    public void setNextPollAt(LocalDateTime nextPollAt) { this.nextPollAt = nextPollAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public LocalDateTime getSubmissionStartedAt() { return submissionStartedAt; }
    public void setSubmissionStartedAt(LocalDateTime submissionStartedAt) { this.submissionStartedAt = submissionStartedAt; }
    public LocalDateTime getReconcileStartedAt() { return reconcileStartedAt; }
    public void setReconcileStartedAt(LocalDateTime reconcileStartedAt) { this.reconcileStartedAt = reconcileStartedAt; }
    public LocalDateTime getLastEngineHeartbeatAt() { return lastEngineHeartbeatAt; }
    public void setLastEngineHeartbeatAt(LocalDateTime lastEngineHeartbeatAt) { this.lastEngineHeartbeatAt = lastEngineHeartbeatAt; }
    public LocalDateTime getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(LocalDateTime deadlineAt) { this.deadlineAt = deadlineAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
