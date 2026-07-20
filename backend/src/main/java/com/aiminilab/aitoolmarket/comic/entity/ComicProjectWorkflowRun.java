package com.aiminilab.aitoolmarket.comic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("comic_project_workflow_runs")
public class ComicProjectWorkflowRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long projectId;
    private Long episodeId;
    private Long shotId;
    private Long workflowRunId;
    private Long rootTaskId;
    private String launchSource;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getEpisodeId() { return episodeId; }
    public void setEpisodeId(Long episodeId) { this.episodeId = episodeId; }
    public Long getShotId() { return shotId; }
    public void setShotId(Long shotId) { this.shotId = shotId; }
    public Long getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(Long workflowRunId) { this.workflowRunId = workflowRunId; }
    public Long getRootTaskId() { return rootTaskId; }
    public void setRootTaskId(Long rootTaskId) { this.rootTaskId = rootTaskId; }
    public String getLaunchSource() { return launchSource; }
    public void setLaunchSource(String launchSource) { this.launchSource = launchSource; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
