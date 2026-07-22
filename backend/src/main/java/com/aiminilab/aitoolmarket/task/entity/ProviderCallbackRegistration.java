package com.aiminilab.aitoolmarket.task.entity;

import java.time.LocalDateTime;

public class ProviderCallbackRegistration {
    private Long id;
    private String providerCode;
    private Long taskId;
    private Long routeAttemptId;
    private String tokenHash;
    private String providerTaskId;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getRouteAttemptId() { return routeAttemptId; }
    public void setRouteAttemptId(Long routeAttemptId) { this.routeAttemptId = routeAttemptId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getProviderTaskId() { return providerTaskId; }
    public void setProviderTaskId(String providerTaskId) { this.providerTaskId = providerTaskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
