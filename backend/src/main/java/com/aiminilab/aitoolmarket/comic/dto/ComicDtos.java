package com.aiminilab.aitoolmarket.comic.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class ComicDtos {
    private ComicDtos() {
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 1000) String description,
            @Size(max = 16) String aspectRatio,
            @Size(max = 1000) String visualStyle) {
    }

    public record UpdateProjectRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 1000) String description,
            @Size(max = 16) String aspectRatio,
            @Size(max = 1000) String visualStyle,
            @NotNull @Min(0) Long expectedRevision) {
    }

    public record ProjectSummary(
            Long id, String title, String description, String aspectRatio, String visualStyle,
            String status, long revision, int episodeCount, LocalDateTime createdAt, LocalDateTime updatedAt,
            String workspacePath) {
    }

    public record ProjectListResponse(List<ProjectSummary> projects) {
    }

    public record ProjectDetail(
            Long id, String title, String description, String aspectRatio, String visualStyle,
            String status, long revision, List<EpisodeSummary> episodes,
            List<CharacterDetail> characters, List<SceneDetail> scenes,
            LocalDateTime createdAt, LocalDateTime updatedAt, String workspacePath) {
    }

    public record CreateEpisodeRequest(
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 500000) String scriptText,
            @Size(max = 32) String sourceType,
            @Min(1) Integer episodeNo) {
    }

    public record GenerateEpisodeRequest(
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 500000) String prompt,
            @Min(1) Integer episodeNo,
            @NotBlank @Size(max = 96) String clientRequestId) {
    }

    public record GenerateStoryboardRequest(
            @NotNull @Min(0) Long expectedRevision,
            @NotBlank @Size(max = 96) String clientRequestId) {
    }

    public record UpdateEpisodeRequest(
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 500000) String scriptText,
            @NotNull @Min(0) Long expectedRevision) {
    }

    public record EpisodeSummary(
            Long id, Integer episodeNo, String title, String scriptSourceType, String status,
            long revision, int shotCount, int durationMs, LocalDateTime storyboardLockedAt,
            LocalDateTime assetsConfirmedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record EpisodeDetail(
            Long id, Long projectId, Integer episodeNo, String title, String scriptSourceType,
            String scriptFileName, String scriptText, String status, long revision,
            List<ShotDetail> shots, LocalDateTime storyboardLockedAt,
            LocalDateTime assetsConfirmedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record ReplaceShotsRequest(@NotNull @Size(max = 18) List<@Valid ShotInput> shots) {
    }

    public record ShotInput(
            Long id,
            @Size(max = 36) String shotKey,
            @Min(1) Integer sequenceNo,
            @NotNull @Min(1000) @Max(30000) Integer durationMs,
            @Size(max = 64) String shotScale,
            @Size(max = 128) String cameraAngle,
            @Size(max = 128) String cameraMovement,
            @Size(max = 128) String emotion,
            @NotBlank @Size(max = 10000) String visualDescription,
            @Size(max = 10000) String dialogue,
            @Size(max = 10000) String narration,
            @Size(max = 1000) String soundEffect,
            @Size(max = 1000) String bgmCue,
            @Size(max = 10000) String firstFramePrompt,
            @Size(max = 10000) String videoPrompt,
            @Size(max = 10000) String negativePrompt,
            List<Long> characterVersionIds,
            Long sceneVersionId,
            Long dependsOnShotId) {
    }

    public record ShotDetail(
            Long id, String shotKey, Integer sequenceNo, Integer durationMs,
            String shotScale, String cameraAngle, String cameraMovement, String emotion,
            String visualDescription, String dialogue, String narration, String soundEffect,
            String bgmCue, String firstFramePrompt, String videoPrompt, String negativePrompt,
            List<Long> characterVersionIds, Long sceneVersionId, Long dependsOnShotId,
            Long selectedAttemptId, ShotAttemptDetail selectedAttempt, String status, long revision) {
    }

    public record RevisionRequest(@NotNull @Min(0) Long expectedRevision) {
    }

    public record UpdateAssetRefsRequest(
            @NotNull List<Long> characterVersionIds,
            Long sceneVersionId,
            @NotNull @Min(0) Long expectedRevision) {
    }

    public record SelectAttemptRequest(
            @NotNull Long attemptId,
            @NotNull @Min(0) Long expectedRevision) {
    }

    public record CreateCharacterRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 2000) String description,
            JsonNode voiceConfig) {
    }

    public record CreateCharacterVersionRequest(
            @NotBlank @Size(max = 10000) String visualPrompt,
            @Size(max = 2048) String frontImageUrl,
            @Size(max = 2048) String sideImageUrl,
            @Size(max = 2048) String backImageUrl,
            @Size(max = 32) String status) {
    }

    public record CharacterDetail(
            Long id, String name, String description, JsonNode voiceConfig,
            String status, List<CharacterVersionDetail> versions) {
    }

    public record CharacterVersionDetail(
            Long id, Integer versionNo, String visualPrompt, String frontImageUrl,
            String sideImageUrl, String backImageUrl, String status, LocalDateTime createdAt) {
    }

    public record CreateSceneRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 2000) String description) {
    }

    public record CreateSceneVersionRequest(
            @NotBlank @Size(max = 10000) String visualPrompt,
            @Size(max = 2048) String anchorImageUrl,
            @Size(max = 32) String status) {
    }

    public record SceneDetail(
            Long id, String name, String description, String status, List<SceneVersionDetail> versions) {
    }

    public record SceneVersionDetail(
            Long id, Integer versionNo, String visualPrompt, String anchorImageUrl,
            String status, LocalDateTime createdAt) {
    }

    public record GenerateAssetVersionRequest(
            @NotBlank @Size(max = 96) String clientRequestId) {
    }

    public record AssetGenerationDetail(
            Long versionId, String status, Long workflowRunId, Long rootTaskId) {
    }

    public record CreateBatchRequest(
            @NotBlank @Size(max = 128) String clientRequestId,
            @Size(max = 128) String toolCode,
            @Min(1) @Max(8) Integer maxParallelism,
            @Min(0) Integer estimatedCredits,
            @NotNull Boolean confirmed,
            @Size(max = 18) List<Long> shotIds) {
    }

    public record RetryShotRequest(
            @NotBlank @Size(max = 128) String clientRequestId,
            @Size(max = 128) String toolCode) {
    }

    public record BatchDetail(
            Long id, Long projectId, Long episodeId, String batchType, String toolCode,
            String clientRequestId, int maxParallelism, Integer estimatedCredits, String status,
            int totalCount, int pendingCount, int runningCount, int succeededCount, int failedCount,
            List<ShotAttemptDetail> attempts, LocalDateTime confirmedAt,
            LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt) {
    }

    public record ShotAttemptDetail(
            Long id, Long shotId, Integer attemptNo, String status, Long workflowRunId,
            Long rootTaskId, JsonNode result, String errorCode, String errorMessage,
            LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record CreateAssemblyBatchRequest(
            @NotBlank @Size(max = 128) String clientRequestId,
            @NotNull Boolean confirmed) {
    }

    public record AssemblyBatchDetail(
            Long id, Long projectId, Long episodeId, String toolCode, String clientRequestId,
            int shotCount, List<Long> selectedAttemptIds, String status, Long workflowRunId,
            Long rootTaskId, String finalVideoUrl, String subtitleUrl, JsonNode result,
            String errorCode, String errorMessage, LocalDateTime confirmedAt,
            LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt) {
    }

    public record WorkspaceBinding(
            Long projectId, Long episodeId, Long shotId, Long rootTaskId, Long workflowRunId,
            String launchSource, String workspacePath) {
    }
}
