package com.aiminilab.aitoolmarket.comic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("comic_assembly_batches")
public class ComicAssemblyBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long projectId;
    private Long episodeId;
    private String toolCode;
    private String clientRequestId;
    private Integer shotCount;
    private String status;
    private Long workflowRunId;
    private Long rootTaskId;
    private String selectedShotsJson;
    private String resultJson;
    private String finalVideoUrl;
    private String subtitleUrl;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime confirmedAt;
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
    public Long getEpisodeId() { return episodeId; }
    public void setEpisodeId(Long episodeId) { this.episodeId = episodeId; }
    public String getToolCode() { return toolCode; }
    public void setToolCode(String toolCode) { this.toolCode = toolCode; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public Integer getShotCount() { return shotCount; }
    public void setShotCount(Integer shotCount) { this.shotCount = shotCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(Long workflowRunId) { this.workflowRunId = workflowRunId; }
    public Long getRootTaskId() { return rootTaskId; }
    public void setRootTaskId(Long rootTaskId) { this.rootTaskId = rootTaskId; }
    public String getSelectedShotsJson() { return selectedShotsJson; }
    public void setSelectedShotsJson(String selectedShotsJson) { this.selectedShotsJson = selectedShotsJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getFinalVideoUrl() { return finalVideoUrl; }
    public void setFinalVideoUrl(String finalVideoUrl) { this.finalVideoUrl = finalVideoUrl; }
    public String getSubtitleUrl() { return subtitleUrl; }
    public void setSubtitleUrl(String subtitleUrl) { this.subtitleUrl = subtitleUrl; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
