package com.aiminilab.aitoolmarket.comic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("comic_character_versions")
public class ComicCharacterVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long characterId;
    private Integer versionNo;
    private String visualPrompt;
    private String frontImageUrl;
    private String sideImageUrl;
    private String backImageUrl;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCharacterId() { return characterId; }
    public void setCharacterId(Long characterId) { this.characterId = characterId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getVisualPrompt() { return visualPrompt; }
    public void setVisualPrompt(String visualPrompt) { this.visualPrompt = visualPrompt; }
    public String getFrontImageUrl() { return frontImageUrl; }
    public void setFrontImageUrl(String frontImageUrl) { this.frontImageUrl = frontImageUrl; }
    public String getSideImageUrl() { return sideImageUrl; }
    public void setSideImageUrl(String sideImageUrl) { this.sideImageUrl = sideImageUrl; }
    public String getBackImageUrl() { return backImageUrl; }
    public void setBackImageUrl(String backImageUrl) { this.backImageUrl = backImageUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
