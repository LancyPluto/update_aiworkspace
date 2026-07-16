package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentToolCallMapper extends BaseMapper<AgentToolCall> {

    @Insert("""
            INSERT INTO agent_tool_calls(run_id, user_id, tool_code, task_id, status, arguments_json, result_json,
                                         error_code, error_message, started_at, finished_at, created_at)
            VALUES(#{call.runId}, #{call.userId}, #{call.toolCode}, #{call.taskId}, #{call.status}, #{call.argumentsJson}, #{call.resultJson},
                   #{call.errorCode}, #{call.errorMessage}, #{call.startedAt}, #{call.finishedAt}, #{call.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "call.id")
    void insertToolCall(@Param("call") AgentToolCall call);

    @Select("""
            SELECT *
            FROM agent_tool_calls
            WHERE id = #{toolCallId}
            LIMIT 1
            """)
    AgentToolCall selectDetailById(@Param("toolCallId") Long toolCallId);

    default Optional<AgentToolCall> findById(Long toolCallId) {
        return Optional.ofNullable(selectDetailById(toolCallId));
    }

    @Select("""
            SELECT *
            FROM agent_tool_calls
            WHERE id = #{toolCallId}
            LIMIT 1
            FOR UPDATE
            """)
    AgentToolCall selectDetailByIdForUpdate(@Param("toolCallId") Long toolCallId);

    default Optional<AgentToolCall> findByIdForUpdate(Long toolCallId) {
        return Optional.ofNullable(selectDetailByIdForUpdate(toolCallId));
    }

    @Select("""
            SELECT *
            FROM agent_tool_calls
            WHERE run_id = #{runId}
            ORDER BY id ASC
            """)
    List<AgentToolCall> findByRunId(@Param("runId") Long runId);

    @Select("""
            SELECT c.*
            FROM agent_tool_calls c
            WHERE c.user_id = #{userId}
              AND c.status = 'SUCCESS'
              AND c.result_json IS NOT NULL
              AND c.run_id IN (
                SELECT r.id
                FROM agent_runs r
                WHERE r.session_id = #{sessionId}
                  AND r.user_id = #{userId}
                  AND r.id < #{runId}
              )
            ORDER BY c.id DESC
            LIMIT #{limit}
            """)
    List<AgentToolCall> findRecentSuccessfulBeforeRun(@Param("userId") Long userId,
                                                      @Param("sessionId") Long sessionId,
                                                      @Param("runId") Long runId,
                                                      @Param("limit") int limit);

    @Select("""
            <script>
            SELECT c.*
            FROM agent_tool_calls c
            JOIN agent_runs r ON r.id = c.run_id
            WHERE c.user_id = #{userId}
              AND r.session_id = #{sessionId}
              AND c.status = 'SUCCESS'
              AND c.result_json IS NOT NULL
              AND c.run_id IN
              <foreach item="runId" collection="runIds" open="(" separator="," close=")">#{runId}</foreach>
            ORDER BY c.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<AgentToolCall> findRecentSuccessfulByRunIds(@Param("userId") Long userId,
                                                     @Param("sessionId") Long sessionId,
                                                     @Param("runIds") List<Long> runIds,
                                                     @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_tool_calls
            WHERE task_id = #{taskId}
            ORDER BY id ASC
            LIMIT 1
            """)
    AgentToolCall selectByTaskId(@Param("taskId") Long taskId);

    default Optional<AgentToolCall> findByTaskId(Long taskId) {
        return Optional.ofNullable(selectByTaskId(taskId));
    }

    @Select("""
            SELECT call_record.*
            FROM agent_tool_calls call_record
            JOIN workflow_runs workflow
              ON workflow.id = #{workflowRunId}
             AND workflow.root_task_id = call_record.task_id
            JOIN ai_tasks root_task
              ON root_task.id = call_record.task_id
             AND root_task.user_id = workflow.user_id
            JOIN ai_tools workflow_tool
              ON workflow_tool.id = workflow.tool_id
             AND workflow_tool.tool_code = call_record.tool_code
            WHERE call_record.task_id = #{rootTaskId}
              AND call_record.status = 'DELEGATED'
              AND call_record.user_id = workflow.user_id
            ORDER BY call_record.id DESC
            LIMIT 1
            """)
    AgentToolCall selectDelegatedByWorkflow(@Param("rootTaskId") Long rootTaskId,
                                            @Param("workflowRunId") Long workflowRunId);

    default Optional<AgentToolCall> findDelegatedByWorkflow(Long rootTaskId, Long workflowRunId) {
        return Optional.ofNullable(selectDelegatedByWorkflow(rootTaskId, workflowRunId));
    }

    @Select("""
            SELECT *
            FROM agent_tool_calls
            WHERE run_id = #{runId}
              AND tool_code = #{toolCode}
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentToolCall selectLatestByRunIdAndToolCode(@Param("runId") Long runId, @Param("toolCode") String toolCode);

    @Update("""
            UPDATE agent_tool_calls
            SET task_id = #{taskId}
            WHERE id = #{toolCallId}
              AND task_id IS NULL
              AND status = 'RUNNING'
            """)
    int bindTaskId(@Param("toolCallId") Long toolCallId, @Param("taskId") Long taskId);

    @Update("""
            UPDATE agent_tool_calls
            SET task_id = #{taskId}, status = 'DELEGATED'
            WHERE id = #{toolCallId}
              AND task_id IS NULL
              AND status = 'RUNNING'
            """)
    int markDelegated(@Param("toolCallId") Long toolCallId,
                      @Param("taskId") Long taskId);

    @Update("""
            UPDATE agent_tool_calls
            SET status = 'SUCCESS', result_json = #{resultJson}, finished_at = #{now}
            WHERE id = #{toolCallId}
              AND status = 'RUNNING'
            """)
    int markSuccess(@Param("toolCallId") Long toolCallId,
                     @Param("resultJson") String resultJson,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_tool_calls
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage}, finished_at = #{now}
            WHERE id = #{toolCallId}
              AND status = 'RUNNING'
            """)
    int markFailed(@Param("toolCallId") Long toolCallId,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_tool_calls
            SET status = #{nextStatus},
                result_json = #{resultJson},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                finished_at = #{now}
            WHERE id = #{toolCallId}
              AND task_id = #{taskId}
              AND status = 'DELEGATED'
            """)
    int finishDelegated(@Param("toolCallId") Long toolCallId,
                        @Param("taskId") Long taskId,
                        @Param("nextStatus") String nextStatus,
                        @Param("resultJson") String resultJson,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage,
                        @Param("now") LocalDateTime now);

    @Select("""
            SELECT c.*
            FROM agent_tool_calls c
            JOIN agent_runs r ON r.id = c.run_id
            WHERE c.user_id = #{userId}
              AND r.session_id = #{sessionId}
              AND (#{toolCode} IS NULL OR #{toolCode} = '' OR c.tool_code = #{toolCode})
              AND (
                #{query} IS NULL OR #{query} = ''
                OR LOWER(c.tool_code) LIKE CONCAT('%', LOWER(#{query}), '%')
                OR LOWER(COALESCE(c.arguments_json, '')) LIKE CONCAT('%', LOWER(#{query}), '%')
                OR LOWER(COALESCE(c.result_json, '')) LIKE CONCAT('%', LOWER(#{query}), '%')
              )
            ORDER BY c.id DESC
            LIMIT #{limit}
            """)
    List<AgentToolCall> searchBySession(@Param("userId") Long userId,
                                        @Param("sessionId") Long sessionId,
                                        @Param("query") String query,
                                        @Param("toolCode") String toolCode,
                                        @Param("limit") int limit);

    @Select("""
            <script>
            SELECT *
            FROM agent_tool_calls
            WHERE task_id IN
            <foreach item="id" collection="taskIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<AgentToolCall> batchFindByTaskIds(@Param("taskIds") List<Long> taskIds);
}
