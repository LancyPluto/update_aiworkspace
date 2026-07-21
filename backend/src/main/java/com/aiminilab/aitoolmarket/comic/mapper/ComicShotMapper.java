package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ComicShotMapper extends BaseMapper<ComicShot> {
    @Select("SELECT * FROM comic_shots WHERE episode_id = #{episodeId} ORDER BY sequence_no, id")
    List<ComicShot> selectByEpisode(@Param("episodeId") Long episodeId);

    @Select("SELECT * FROM comic_shots WHERE id = #{shotId} AND episode_id = #{episodeId} LIMIT 1")
    ComicShot selectInEpisode(@Param("shotId") Long shotId, @Param("episodeId") Long episodeId);

    @Update("UPDATE comic_shots SET sequence_no = sequence_no + 1000 WHERE episode_id = #{episodeId}")
    int shiftSequences(@Param("episodeId") Long episodeId);

    @Update("""
            UPDATE comic_shots SET sequence_no = #{shot.sequenceNo}, duration_ms = #{shot.durationMs},
              shot_scale = #{shot.shotScale}, camera_angle = #{shot.cameraAngle},
              camera_movement = #{shot.cameraMovement}, emotion = #{shot.emotion},
              visual_description = #{shot.visualDescription}, dialogue = #{shot.dialogue},
              narration = #{shot.narration}, sound_effect = #{shot.soundEffect}, bgm_cue = #{shot.bgmCue},
              first_frame_prompt = #{shot.firstFramePrompt}, video_prompt = #{shot.videoPrompt},
              negative_prompt = #{shot.negativePrompt}, character_version_ids_json = #{shot.characterVersionIdsJson},
              scene_version_id = #{shot.sceneVersionId}, depends_on_shot_id = #{shot.dependsOnShotId},
              revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{shot.id} AND episode_id = #{shot.episodeId}
            """)
    int updateDraft(@Param("shot") ComicShot shot);

    @Update("""
            UPDATE comic_shots SET character_version_ids_json = #{characterVersionIdsJson},
              scene_version_id = #{sceneVersionId}, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{shotId} AND episode_id = #{episodeId} AND revision = #{expectedRevision}
            """)
    int updateAssetRefs(@Param("shotId") Long shotId, @Param("episodeId") Long episodeId,
                        @Param("expectedRevision") Long expectedRevision,
                        @Param("characterVersionIdsJson") String characterVersionIdsJson,
                        @Param("sceneVersionId") Long sceneVersionId);

    @Update("""
            UPDATE comic_shots SET selected_attempt_id = #{attemptId}, revision = revision + 1,
              status = 'GENERATED', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{shotId} AND episode_id = #{episodeId} AND revision = #{expectedRevision}
            """)
    int selectAttempt(@Param("shotId") Long shotId, @Param("episodeId") Long episodeId,
                      @Param("attemptId") Long attemptId, @Param("expectedRevision") Long expectedRevision);

    @Update("""
            UPDATE comic_shots SET selected_attempt_id = #{attemptId}, status = 'GENERATED',
              revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{shotId} AND selected_attempt_id IS NULL
            """)
    int selectFirstSuccessfulAttempt(@Param("shotId") Long shotId, @Param("attemptId") Long attemptId);

    @Delete("DELETE FROM comic_shots WHERE episode_id = #{episodeId}")
    int deleteByEpisode(@Param("episodeId") Long episodeId);
}
