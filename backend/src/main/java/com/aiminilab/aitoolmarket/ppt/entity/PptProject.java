package com.aiminilab.aitoolmarket.ppt.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ppt_projects")
public class PptProject {
    @TableId
    private Long id;
    private Long userId;
    private Long toolId;
    private String title;
    private String topic;
    private String creationType;
    private String language;
    private String aspectRatio;
    private Integer pageCount;
    private String status;
    private String engineStrategy;
    private Long textModelConfigId;
    private Long imageModelConfigId;
    private Long currentDeckVersionId;
    private Long legacyBindingId;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getCreationType() { return creationType; }
    public void setCreationType(String creationType) { this.creationType = creationType; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(String aspectRatio) { this.aspectRatio = aspectRatio; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEngineStrategy() { return engineStrategy; }
    public void setEngineStrategy(String engineStrategy) { this.engineStrategy = engineStrategy; }
    public Long getTextModelConfigId() { return textModelConfigId; }
    public void setTextModelConfigId(Long textModelConfigId) { this.textModelConfigId = textModelConfigId; }
    public Long getImageModelConfigId() { return imageModelConfigId; }
    public void setImageModelConfigId(Long imageModelConfigId) { this.imageModelConfigId = imageModelConfigId; }
    public Long getCurrentDeckVersionId() { return currentDeckVersionId; }
    public void setCurrentDeckVersionId(Long currentDeckVersionId) { this.currentDeckVersionId = currentDeckVersionId; }
    public Long getLegacyBindingId() { return legacyBindingId; }
    public void setLegacyBindingId(Long legacyBindingId) { this.legacyBindingId = legacyBindingId; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
