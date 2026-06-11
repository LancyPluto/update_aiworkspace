package com.aiminilab.aitoolmarket.workflow.mapper;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

public interface WorkflowRunStepMapper extends BaseMapper<WorkflowRunStep> {

    @Select("SELECT * FROM workflow_run_steps WHERE run_id = #{runId} ORDER BY id ASC")
    List<WorkflowRunStep> selectByRunId(@Param("runId") Long runId);

    @Select("SELECT * FROM workflow_run_steps WHERE task_id = #{taskId} LIMIT 1")
    WorkflowRunStep selectByTaskId(@Param("taskId") Long taskId);

    default Optional<WorkflowRunStep> findByTaskId(Long taskId) {
        return Optional.ofNullable(selectByTaskId(taskId));
    }

    @Select("SELECT * FROM workflow_run_steps WHERE run_id = #{runId} AND node_id = #{nodeId} LIMIT 1")
    WorkflowRunStep selectByRunIdAndNodeId(@Param("runId") Long runId, @Param("nodeId") String nodeId);

    @Update("""
            UPDATE workflow_run_steps
            SET status = #{status},
                task_id = #{taskId},
                attempt = #{attempt},
                input_json = #{inputJson},
                output_json = #{outputJson},
                error_message = #{errorMessage},
                started_at = #{startedAt},
                finished_at = #{finishedAt}
            WHERE id = #{stepId}
            """)
    int updateStepState(@Param("stepId") Long stepId,
                        @Param("status") String status,
                        @Param("taskId") Long taskId,
                        @Param("attempt") Integer attempt,
                        @Param("inputJson") String inputJson,
                        @Param("outputJson") String outputJson,
                        @Param("errorMessage") String errorMessage,
                        @Param("startedAt") java.time.LocalDateTime startedAt,
                        @Param("finishedAt") java.time.LocalDateTime finishedAt);
}
