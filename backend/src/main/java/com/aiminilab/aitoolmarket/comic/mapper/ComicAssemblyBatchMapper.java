package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicAssemblyBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ComicAssemblyBatchMapper extends BaseMapper<ComicAssemblyBatch> {
    @Select("SELECT * FROM comic_assembly_batches WHERE id = #{batchId} AND user_id = #{userId} LIMIT 1")
    ComicAssemblyBatch selectOwned(@Param("batchId") Long batchId, @Param("userId") Long userId);

    @Select("SELECT * FROM comic_assembly_batches WHERE user_id = #{userId} AND client_request_id = #{clientRequestId} LIMIT 1")
    ComicAssemblyBatch selectByRequest(@Param("userId") Long userId,
                                       @Param("clientRequestId") String clientRequestId);

    @Select("""
            SELECT * FROM comic_assembly_batches
            WHERE user_id = #{userId} AND project_id = #{projectId} AND episode_id = #{episodeId}
            ORDER BY id DESC LIMIT 1
            """)
    ComicAssemblyBatch selectLatest(@Param("userId") Long userId,
                                    @Param("projectId") Long projectId,
                                    @Param("episodeId") Long episodeId);

    @Select("""
            SELECT * FROM comic_assembly_batches
            WHERE episode_id = #{episodeId}
              AND status IN ('CREATING','RUNNING','AWAITING_USER','AWAITING_FUNDS','CANCELLING')
            ORDER BY id DESC LIMIT 1
            """)
    ComicAssemblyBatch selectActiveByEpisode(@Param("episodeId") Long episodeId);

    @Select("""
            SELECT id FROM comic_assembly_batches
            WHERE workflow_run_id IS NOT NULL
              AND status IN ('RUNNING','AWAITING_USER','AWAITING_FUNDS','CANCELLING')
            ORDER BY updated_at, id LIMIT #{limit}
            """)
    List<Long> selectReconcilableIds(@Param("limit") int limit);

    @Update("""
            UPDATE comic_assembly_batches
            SET workflow_run_id = #{workflowRunId}, root_task_id = #{rootTaskId}, status = #{status},
                started_at = COALESCE(started_at, #{startedAt}), updated_at = CURRENT_TIMESTAMP
            WHERE id = #{batchId} AND status = 'CREATING'
            """)
    int bindWorkflow(@Param("batchId") Long batchId,
                     @Param("workflowRunId") Long workflowRunId,
                     @Param("rootTaskId") Long rootTaskId,
                     @Param("status") String status,
                     @Param("startedAt") LocalDateTime startedAt);

    @Update("""
            UPDATE comic_assembly_batches SET status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{batchId}
              AND status IN ('CREATING','RUNNING','AWAITING_USER','AWAITING_FUNDS','CANCELLING')
            """)
    int syncActiveStatus(@Param("batchId") Long batchId, @Param("status") String status);

    @Update("""
            UPDATE comic_assembly_batches
            SET status = 'SUCCESS', result_json = #{resultJson}, final_video_url = #{finalVideoUrl},
                subtitle_url = #{subtitleUrl}, error_code = NULL, error_message = NULL,
                finished_at = #{finishedAt}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{batchId}
              AND status IN ('CREATING','RUNNING','AWAITING_USER','AWAITING_FUNDS','CANCELLING')
            """)
    int completeSuccess(@Param("batchId") Long batchId,
                        @Param("resultJson") String resultJson,
                        @Param("finalVideoUrl") String finalVideoUrl,
                        @Param("subtitleUrl") String subtitleUrl,
                        @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE comic_assembly_batches
            SET status = #{status}, error_code = #{errorCode}, error_message = #{errorMessage},
                finished_at = #{finishedAt}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{batchId}
              AND status IN ('CREATING','RUNNING','AWAITING_USER','AWAITING_FUNDS','CANCELLING')
            """)
    int completeFailure(@Param("batchId") Long batchId,
                        @Param("status") String status,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage,
                        @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE comic_episodes SET status = 'COMPLETED', revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{episodeId} AND status IN ('ASSETS_CONFIRMED','GENERATING')
            """)
    int markEpisodeCompleted(@Param("episodeId") Long episodeId);
}
