package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ComicEpisodeMapper extends BaseMapper<ComicEpisode> {
    @Select("SELECT COALESCE(MAX(episode_no), 0) FROM comic_episodes WHERE project_id = #{projectId}")
    int maxEpisodeNo(@Param("projectId") Long projectId);

    @Select("SELECT * FROM comic_episodes WHERE project_id = #{projectId} ORDER BY episode_no, id")
    List<ComicEpisode> selectByProject(@Param("projectId") Long projectId);

    @Select("""
            SELECT e.* FROM comic_episodes e
            JOIN comic_projects p ON p.id = e.project_id
            WHERE e.id = #{episodeId} AND e.project_id = #{projectId}
              AND p.user_id = #{userId} AND p.status <> 'DELETED' LIMIT 1
            """)
    ComicEpisode selectOwned(@Param("projectId") Long projectId, @Param("episodeId") Long episodeId,
                             @Param("userId") Long userId);

    @Select("""
            SELECT e.* FROM comic_episodes e
            JOIN comic_projects p ON p.id = e.project_id
            WHERE e.id = #{episodeId} AND e.project_id = #{projectId}
              AND p.user_id = #{userId} AND p.status <> 'DELETED' LIMIT 1 FOR UPDATE
            """)
    ComicEpisode selectOwnedForUpdate(@Param("projectId") Long projectId, @Param("episodeId") Long episodeId,
                                      @Param("userId") Long userId);

    @Update("""
            UPDATE comic_episodes SET title = #{title}, script_text = #{scriptText},
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND revision = #{expectedRevision} AND status = 'DRAFT'
            """)
    int updateDraft(@Param("episodeId") Long episodeId, @Param("expectedRevision") Long expectedRevision,
                    @Param("title") String title, @Param("scriptText") String scriptText);

    @Update("""
            UPDATE comic_episodes SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND revision = #{expectedRevision} AND status = 'DRAFT'
            """)
    int touchDraft(@Param("episodeId") Long episodeId, @Param("expectedRevision") Long expectedRevision);

    @Update("""
            UPDATE comic_episodes SET status = 'STORYBOARD_LOCKED', storyboard_locked_at = CURRENT_TIMESTAMP,
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND revision = #{expectedRevision} AND status = 'DRAFT'
            """)
    int lockStoryboard(@Param("episodeId") Long episodeId, @Param("expectedRevision") Long expectedRevision);

    @Update("""
            UPDATE comic_episodes SET status = 'ASSETS_CONFIRMED', assets_confirmed_at = CURRENT_TIMESTAMP,
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND revision = #{expectedRevision} AND status = 'STORYBOARD_LOCKED'
            """)
    int confirmAssets(@Param("episodeId") Long episodeId, @Param("expectedRevision") Long expectedRevision);

    @Update("""
            UPDATE comic_episodes SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND revision = #{expectedRevision} AND status = 'STORYBOARD_LOCKED'
            """)
    int touchLocked(@Param("episodeId") Long episodeId, @Param("expectedRevision") Long expectedRevision);

    @Update("""
            UPDATE comic_episodes SET status = 'GENERATING', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND status IN ('ASSETS_CONFIRMED', 'GENERATING')
            """)
    int markGenerating(@Param("episodeId") Long episodeId);

    @Update("""
            UPDATE comic_episodes SET status = 'STORYBOARD_GENERATING', revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND revision = #{expectedRevision} AND status = 'DRAFT'
            """)
    int startStoryboardGeneration(@Param("episodeId") Long episodeId,
                                  @Param("expectedRevision") Long expectedRevision);

    @Update("""
            UPDATE comic_episodes SET title = #{title}, script_source_type = 'AI',
                script_text = #{scriptText}, status = 'DRAFT', revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND status IN ('SCRIPT_GENERATING', 'DRAFT')
            """)
    int completeScriptProjection(@Param("episodeId") Long episodeId,
                                 @Param("title") String title,
                                 @Param("scriptText") String scriptText);

    @Update("""
            UPDATE comic_episodes SET status = 'DRAFT', revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND status IN ('STORYBOARD_GENERATING', 'DRAFT')
            """)
    int completeStoryboardProjection(@Param("episodeId") Long episodeId);

    @Update("""
            UPDATE comic_episodes SET status = 'DRAFT', revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND status IN ('SCRIPT_GENERATING', 'STORYBOARD_GENERATING')
            """)
    int resetGenerationStatus(@Param("episodeId") Long episodeId);

}
