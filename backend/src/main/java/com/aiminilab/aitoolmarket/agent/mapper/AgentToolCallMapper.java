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
            INSERT INTO agent_tool_calls(run_id, user_id, tool_code, status, arguments_json, result_json,
                                         error_code, error_message, started_at, finished_at, created_at)
            VALUES(#{call.runId}, #{call.userId}, #{call.toolCode}, #{call.status}, #{call.argumentsJson}, #{call.resultJson},
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
            SET status = 'SUCCESS', result_json = #{resultJson}, finished_at = #{now}
            WHERE id = #{toolCallId}
            """)
    void markSuccess(@Param("toolCallId") Long toolCallId,
                     @Param("resultJson") String resultJson,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_tool_calls
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage}, finished_at = #{now}
            WHERE id = #{toolCallId}
            """)
    void markFailed(@Param("toolCallId") Long toolCallId,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("now") LocalDateTime now);
}
