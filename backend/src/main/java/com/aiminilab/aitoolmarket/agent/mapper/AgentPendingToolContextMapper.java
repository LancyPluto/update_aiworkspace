package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentPendingToolContext;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentPendingToolContextMapper extends BaseMapper<AgentPendingToolContext> {

    @Insert("""
            INSERT INTO agent_pending_tool_context(run_id, session_id, user_id, selected_tool_code,
                candidate_tool_codes_json, collected_arguments_json, missing_arguments_json,
                clarifying_question, confirmation_required, source, status, created_at, updated_at)
            VALUES(#{ctx.runId}, #{ctx.sessionId}, #{ctx.userId}, #{ctx.selectedToolCode},
                #{ctx.candidateToolCodesJson}, #{ctx.collectedArgumentsJson}, #{ctx.missingArgumentsJson},
                #{ctx.clarifyingQuestion}, #{ctx.confirmationRequired}, #{ctx.source}, #{ctx.status},
                #{ctx.createdAt}, #{ctx.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "ctx.id")
    void insertPendingContext(@Param("ctx") AgentPendingToolContext ctx);

    @Select("""
            SELECT *
            FROM agent_pending_tool_context
            WHERE run_id = #{runId} AND status = 'ACTIVE'
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentPendingToolContext findActiveByRunId(@Param("runId") Long runId);

    @Select("""
            SELECT *
            FROM agent_pending_tool_context
            WHERE user_id = #{userId} AND status = 'ACTIVE'
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    AgentPendingToolContext findLatestActiveByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM agent_pending_tool_context
            WHERE session_id = #{sessionId} AND status = 'ACTIVE'
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    AgentPendingToolContext findActiveBySessionId(@Param("sessionId") Long sessionId);

    @Update("""
            UPDATE agent_pending_tool_context
            SET status = #{status}, updated_at = NOW()
            WHERE id = #{id} AND status = 'ACTIVE'
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("""
            UPDATE agent_pending_tool_context
            SET status = 'EXPIRED', updated_at = NOW()
            WHERE run_id = #{runId} AND status = 'ACTIVE'
            """)
    int expireByRunId(@Param("runId") Long runId);
}
