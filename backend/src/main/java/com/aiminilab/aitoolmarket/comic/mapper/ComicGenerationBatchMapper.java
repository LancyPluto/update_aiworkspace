package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicGenerationBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ComicGenerationBatchMapper extends BaseMapper<ComicGenerationBatch> {
    @Select("SELECT * FROM comic_generation_batches WHERE id = #{batchId} LIMIT 1 FOR UPDATE")
    ComicGenerationBatch selectByIdForUpdate(@Param("batchId") Long batchId);

    @Select("SELECT * FROM comic_generation_batches WHERE id = #{batchId} AND user_id = #{userId} LIMIT 1")
    ComicGenerationBatch selectOwned(@Param("batchId") Long batchId, @Param("userId") Long userId);

    @Select("SELECT * FROM comic_generation_batches WHERE user_id = #{userId} AND client_request_id = #{clientRequestId} LIMIT 1")
    ComicGenerationBatch selectByRequest(@Param("userId") Long userId,
                                         @Param("clientRequestId") String clientRequestId);

    @Select("""
            SELECT * FROM comic_generation_batches
            WHERE user_id = #{userId} AND project_id = #{projectId} AND episode_id = #{episodeId}
            ORDER BY id DESC LIMIT 1
            """)
    ComicGenerationBatch selectLatest(@Param("userId") Long userId,
                                      @Param("projectId") Long projectId,
                                      @Param("episodeId") Long episodeId);

    @Select("""
            SELECT COUNT(*) FROM comic_generation_batches
            WHERE episode_id = #{episodeId} AND status IN ('CREATED','RUNNING')
            """)
    int countActiveByEpisode(@Param("episodeId") Long episodeId);

    @Select("SELECT id FROM comic_generation_batches WHERE status IN ('CREATED','RUNNING') ORDER BY updated_at LIMIT #{limit}")
    List<Long> selectDispatchableIds(@Param("limit") int limit);

    @Update("""
            UPDATE comic_generation_batches SET status = #{status},
              started_at = COALESCE(started_at, #{startedAt}), finished_at = #{finishedAt},
              updated_at = CURRENT_TIMESTAMP WHERE id = #{batchId}
            """)
    int updateState(@Param("batchId") Long batchId, @Param("status") String status,
                    @Param("startedAt") java.time.LocalDateTime startedAt,
                    @Param("finishedAt") java.time.LocalDateTime finishedAt);

    @Update("""
            UPDATE comic_generation_batches SET status = 'RUNNING',
              started_at = COALESCE(started_at, #{startedAt}), updated_at = CURRENT_TIMESTAMP
            WHERE id = #{batchId} AND status IN ('CREATED','RUNNING')
            """)
    int markRunning(@Param("batchId") Long batchId,
                    @Param("startedAt") java.time.LocalDateTime startedAt);
}
