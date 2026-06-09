package com.aiminilab.aitoolmarket.user.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountDataCleanupMapper {

    @Delete("DELETE FROM agent_file_chunks WHERE user_id = #{userId}")
    int deleteAgentFileChunks(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_files WHERE user_id = #{userId}")
    int deleteAgentFiles(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_pending_tool_context WHERE user_id = #{userId}")
    int deleteAgentPendingToolContext(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_tool_calls WHERE user_id = #{userId}")
    int deleteAgentToolCalls(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_run_events WHERE user_id = #{userId}")
    int deleteAgentRunEvents(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_context_snapshots WHERE user_id = #{userId}")
    int deleteAgentContextSnapshots(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_messages WHERE user_id = #{userId}")
    int deleteAgentMessages(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_runs WHERE user_id = #{userId}")
    int deleteAgentRuns(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_workspace_memory_items WHERE user_id = #{userId}")
    int deleteAgentWorkspaceMemoryItems(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_workspace_members WHERE user_id = #{userId}")
    int deleteAgentWorkspaceMembers(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_workspaces WHERE owner_user_id = #{userId}")
    int deleteAgentWorkspaces(@Param("userId") Long userId);

    @Delete("DELETE FROM agent_tool_preferences WHERE user_id = #{userId}")
    int deleteAgentToolPreferences(@Param("userId") Long userId);

    @Delete("""
            DELETE FROM ai_market_message_attachments
            WHERE message_id IN (
              SELECT message_id FROM ai_market_messages
              WHERE session_id IN (
                SELECT session_id FROM ai_market_sessions WHERE user_id = #{userId}
              )
            )
            """)
    int deleteMarketMessageAttachments(@Param("userId") Long userId);

    @Delete("""
            DELETE FROM ai_market_messages
            WHERE session_id IN (
              SELECT session_id FROM ai_market_sessions WHERE user_id = #{userId}
            )
            """)
    int deleteMarketMessages(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_market_sessions WHERE user_id = #{userId}")
    int deleteMarketSessions(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_market_files WHERE user_id = #{userId}")
    int deleteMarketFiles(@Param("userId") Long userId);
}
