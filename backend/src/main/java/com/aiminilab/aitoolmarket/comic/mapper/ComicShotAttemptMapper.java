package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicShotAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ComicShotAttemptMapper extends BaseMapper<ComicShotAttempt> {
    @Select("SELECT * FROM comic_shot_attempts WHERE batch_id = #{batchId} ORDER BY id")
    List<ComicShotAttempt> selectByBatch(@Param("batchId") Long batchId);

    @Select("SELECT * FROM comic_shot_attempts WHERE batch_id = #{batchId} AND status = 'PENDING' ORDER BY id LIMIT #{limit}")
    List<ComicShotAttempt> selectPending(@Param("batchId") Long batchId, @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(attempt_no), 0) FROM comic_shot_attempts WHERE shot_id = #{shotId}")
    int maxAttemptNo(@Param("shotId") Long shotId);

    @Select("SELECT * FROM comic_shot_attempts WHERE id = #{attemptId} AND shot_id = #{shotId} LIMIT 1")
    ComicShotAttempt selectForShot(@Param("attemptId") Long attemptId, @Param("shotId") Long shotId);

    @Select("SELECT COUNT(*) FROM comic_shot_attempts WHERE shot_id = #{shotId} AND status IN ('PENDING','DISPATCHING','RUNNING','AWAITING_USER','AWAITING_FUNDS')")
    int countActiveByShot(@Param("shotId") Long shotId);

    @Update("""
            UPDATE comic_shot_attempts SET status = 'DISPATCHING', started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
              updated_at = CURRENT_TIMESTAMP WHERE id = #{attemptId} AND status = 'PENDING'
            """)
    int claimForDispatch(@Param("attemptId") Long attemptId);

    @Update("""
            UPDATE comic_shot_attempts SET workflow_run_id = #{workflowRunId}, root_task_id = #{rootTaskId},
              status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId} AND status = 'DISPATCHING'
            """)
    int bindWorkflow(@Param("attemptId") Long attemptId, @Param("workflowRunId") Long workflowRunId,
                     @Param("rootTaskId") Long rootTaskId, @Param("status") String status);

    @Update("""
            UPDATE comic_shot_attempts SET status = #{status}, result_json = #{resultJson},
              error_code = #{errorCode}, error_message = #{errorMessage}, finished_at = #{finishedAt},
              updated_at = CURRENT_TIMESTAMP WHERE id = #{attemptId}
            """)
    int syncState(@Param("attemptId") Long attemptId, @Param("status") String status,
                  @Param("resultJson") String resultJson, @Param("errorCode") String errorCode,
                  @Param("errorMessage") String errorMessage,
                  @Param("finishedAt") java.time.LocalDateTime finishedAt);
}
