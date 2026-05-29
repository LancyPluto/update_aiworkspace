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
            SET status = 'SUCCESS', result_json = #{resultJson}, finished_at = #{now}
            WHERE id = #{toolCallId}
              AND status NOT IN ('SUCCESS', 'FAILED')
            """)
    int markSuccess(@Param("toolCallId") Long toolCallId,
                     @Param("resultJson") String resultJson,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_tool_calls
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage}, finished_at = #{now}
            WHERE id = #{toolCallId}
              AND status NOT IN ('SUCCESS', 'FAILED')
            """)
    int markFailed(@Param("toolCallId") Long toolCallId,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("now") LocalDateTime now);
}
