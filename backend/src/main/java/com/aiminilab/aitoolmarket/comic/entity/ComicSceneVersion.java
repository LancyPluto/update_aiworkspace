package com.aiminilab.aitoolmarket.comic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("comic_scene_versions")
public class ComicSceneVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sceneId;
    private Integer versionNo;
    private String visualPrompt;
    private String anchorImageUrl;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSceneId() { return sceneId; }
    public void setSceneId(Long sceneId) { this.sceneId = sceneId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getVisualPrompt() { return visualPrompt; }
    public void setVisualPrompt(String visualPrompt) { this.visualPrompt = visualPrompt; }
    public String getAnchorImageUrl() { return anchorImageUrl; }
    public void setAnchorImageUrl(String anchorImageUrl) { this.anchorImageUrl = anchorImageUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
