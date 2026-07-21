package com.aiminilab.aitoolmarket.comic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("comic_episodes")
public class ComicEpisode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Integer episodeNo;
    private String title;
    private String scriptSourceType;
    private String scriptFileName;
    private String scriptText;
    private String status;
    private Long revision;
    private LocalDateTime storyboardLockedAt;
    private LocalDateTime assetsConfirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Integer getEpisodeNo() { return episodeNo; }
    public void setEpisodeNo(Integer episodeNo) { this.episodeNo = episodeNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getScriptSourceType() { return scriptSourceType; }
    public void setScriptSourceType(String scriptSourceType) { this.scriptSourceType = scriptSourceType; }
    public String getScriptFileName() { return scriptFileName; }
    public void setScriptFileName(String scriptFileName) { this.scriptFileName = scriptFileName; }
    public String getScriptText() { return scriptText; }
    public void setScriptText(String scriptText) { this.scriptText = scriptText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public LocalDateTime getStoryboardLockedAt() { return storyboardLockedAt; }
    public void setStoryboardLockedAt(LocalDateTime storyboardLockedAt) { this.storyboardLockedAt = storyboardLockedAt; }
    public LocalDateTime getAssetsConfirmedAt() { return assetsConfirmedAt; }
    public void setAssetsConfirmedAt(LocalDateTime assetsConfirmedAt) { this.assetsConfirmedAt = assetsConfirmedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
