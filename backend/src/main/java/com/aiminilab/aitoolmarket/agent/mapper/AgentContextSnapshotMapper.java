package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentContextSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentContextSnapshotMapper extends BaseMapper<AgentContextSnapshot> {

    @Insert("""
            INSERT INTO agent_context_snapshots(run_id, session_id, user_id, workspace_id, model_config_id,
                                                model_provider_code, model_name, strategy, max_history_messages,
                                                history_message_count, file_count, file_chunk_count, memory_item_count,
                                                estimated_input_tokens, snapshot_json, created_at)
            VALUES(#{snapshot.runId}, #{snapshot.sessionId}, #{snapshot.userId}, #{snapshot.workspaceId},
                   #{snapshot.modelConfigId}, #{snapshot.modelProviderCode}, #{snapshot.modelName}, #{snapshot.strategy},
                   #{snapshot.maxHistoryMessages}, #{snapshot.historyMessageCount}, #{snapshot.fileCount},
                   #{snapshot.fileChunkCount}, #{snapshot.memoryItemCount}, #{snapshot.estimatedInputTokens},
                   #{snapshot.snapshotJson}, #{snapshot.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "snapshot.id")
    void insertSnapshot(@Param("snapshot") AgentContextSnapshot snapshot);

    @Select("""
            SELECT *
            FROM agent_context_snapshots
            WHERE run_id = #{runId}
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentContextSnapshot findLatestByRunId(@Param("runId") Long runId);
}
