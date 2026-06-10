package com.aiminilab.aitoolmarket.workflow.mapper;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    @Select("SELECT * FROM workflow_runs WHERE root_task_id = #{rootTaskId} ORDER BY id DESC LIMIT 1")
    WorkflowRun selectByRootTaskId(@Param("rootTaskId") Long rootTaskId);

    default Optional<WorkflowRun> findByRootTaskId(Long rootTaskId) {
        return Optional.ofNullable(selectByRootTaskId(rootTaskId));
    }

    @Update("""
            UPDATE workflow_runs
            SET status = #{status},
                context_json = #{contextJson},
                current_node_id = #{currentNodeId},
                error_message = #{errorMessage},
                finished_at = #{finishedAt}
            WHERE id = #{runId}
            """)
    int updateRunState(@Param("runId") Long runId,
                       @Param("status") String status,
                       @Param("contextJson") String contextJson,
                       @Param("currentNodeId") String currentNodeId,
                       @Param("errorMessage") String errorMessage,
                       @Param("finishedAt") java.time.LocalDateTime finishedAt);

    @Update("""
            UPDATE workflow_runs
            SET status = #{status},
                input_json = #{inputJson},
                context_json = #{contextJson},
                current_node_id = #{currentNodeId},
                error_message = #{errorMessage},
                finished_at = #{finishedAt}
            WHERE id = #{runId}
            """)
    int updateRunStateWithInput(@Param("runId") Long runId,
                                @Param("status") String status,
                                @Param("inputJson") String inputJson,
                                @Param("contextJson") String contextJson,
                                @Param("currentNodeId") String currentNodeId,
                                @Param("errorMessage") String errorMessage,
                                @Param("finishedAt") java.time.LocalDateTime finishedAt);
}
