package com.aiminilab.aitoolmarket.comic.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ComicWorkflowProjectionMapper {

    @Insert("""
            INSERT IGNORE INTO comic_workflow_projections (
                workflow_run_id, projection_type, status, created_at, updated_at
            ) VALUES (
                #{workflowRunId}, #{projectionType}, 'PROJECTING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """)
    int claim(@Param("workflowRunId") Long workflowRunId,
              @Param("projectionType") String projectionType);

    @Update("""
            UPDATE comic_workflow_projections
            SET status = #{status}, projected_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE workflow_run_id = #{workflowRunId} AND status = 'PROJECTING'
            """)
    int complete(@Param("workflowRunId") Long workflowRunId,
                 @Param("status") String status);
}
