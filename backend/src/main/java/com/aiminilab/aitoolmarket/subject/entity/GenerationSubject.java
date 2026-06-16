package com.aiminilab.aitoolmarket.subject.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_generation_subjects")
public class GenerationSubject {
    private Long id;
    private Long userId;
    private String subjectCode;
    private String displayName;
    private String description;
    private String providerCode;
    private String vendorAccountRef;
    private String referenceType;
    private String previewUrl;
    private String referenceJson;
    private String upstreamElementId;
    private String syncTaskId;
    private String syncStatus;
    private String syncError;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getVendorAccountRef() { return vendorAccountRef; }
    public void setVendorAccountRef(String vendorAccountRef) { this.vendorAccountRef = vendorAccountRef; }
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }
    public String getPreviewUrl() { return previewUrl; }
    public void setPreviewUrl(String previewUrl) { this.previewUrl = previewUrl; }
    public String getReferenceJson() { return referenceJson; }
    public void setReferenceJson(String referenceJson) { this.referenceJson = referenceJson; }
    public String getUpstreamElementId() { return upstreamElementId; }
    public void setUpstreamElementId(String upstreamElementId) { this.upstreamElementId = upstreamElementId; }
    public String getSyncTaskId() { return syncTaskId; }
    public void setSyncTaskId(String syncTaskId) { this.syncTaskId = syncTaskId; }
    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }
    public String getSyncError() { return syncError; }
    public void setSyncError(String syncError) { this.syncError = syncError; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
