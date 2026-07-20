package com.aiminilab.aitoolmarket.comic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("comic_shots")
public class ComicShot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long episodeId;
    private String shotKey;
    private Integer sequenceNo;
    private Integer durationMs;
    private String shotScale;
    private String cameraAngle;
    private String cameraMovement;
    private String emotion;
    private String visualDescription;
    private String dialogue;
    private String narration;
    private String soundEffect;
    private String bgmCue;
    private String firstFramePrompt;
    private String videoPrompt;
    private String negativePrompt;
    private String characterVersionIdsJson;
    private Long sceneVersionId;
    private Long dependsOnShotId;
    private Long selectedAttemptId;
    private String status;
    private Long revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEpisodeId() { return episodeId; }
    public void setEpisodeId(Long episodeId) { this.episodeId = episodeId; }
    public String getShotKey() { return shotKey; }
    public void setShotKey(String shotKey) { this.shotKey = shotKey; }
    public Integer getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Integer sequenceNo) { this.sequenceNo = sequenceNo; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public String getShotScale() { return shotScale; }
    public void setShotScale(String shotScale) { this.shotScale = shotScale; }
    public String getCameraAngle() { return cameraAngle; }
    public void setCameraAngle(String cameraAngle) { this.cameraAngle = cameraAngle; }
    public String getCameraMovement() { return cameraMovement; }
    public void setCameraMovement(String cameraMovement) { this.cameraMovement = cameraMovement; }
    public String getEmotion() { return emotion; }
    public void setEmotion(String emotion) { this.emotion = emotion; }
    public String getVisualDescription() { return visualDescription; }
    public void setVisualDescription(String visualDescription) { this.visualDescription = visualDescription; }
    public String getDialogue() { return dialogue; }
    public void setDialogue(String dialogue) { this.dialogue = dialogue; }
    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }
    public String getSoundEffect() { return soundEffect; }
    public void setSoundEffect(String soundEffect) { this.soundEffect = soundEffect; }
    public String getBgmCue() { return bgmCue; }
    public void setBgmCue(String bgmCue) { this.bgmCue = bgmCue; }
    public String getFirstFramePrompt() { return firstFramePrompt; }
    public void setFirstFramePrompt(String firstFramePrompt) { this.firstFramePrompt = firstFramePrompt; }
    public String getVideoPrompt() { return videoPrompt; }
    public void setVideoPrompt(String videoPrompt) { this.videoPrompt = videoPrompt; }
    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    public String getCharacterVersionIdsJson() { return characterVersionIdsJson; }
    public void setCharacterVersionIdsJson(String characterVersionIdsJson) { this.characterVersionIdsJson = characterVersionIdsJson; }
    public Long getSceneVersionId() { return sceneVersionId; }
    public void setSceneVersionId(Long sceneVersionId) { this.sceneVersionId = sceneVersionId; }
    public Long getDependsOnShotId() { return dependsOnShotId; }
    public void setDependsOnShotId(Long dependsOnShotId) { this.dependsOnShotId = dependsOnShotId; }
    public Long getSelectedAttemptId() { return selectedAttemptId; }
    public void setSelectedAttemptId(Long selectedAttemptId) { this.selectedAttemptId = selectedAttemptId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
