package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelRequestSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;

public interface AgentModelRequestSnapshotMapper extends BaseMapper<AgentModelRequestSnapshot> {
    @Insert("""
            INSERT IGNORE INTO agent_model_request_snapshots(
              run_id,user_id,request_sequence,request_stage,iteration_no,model_provider_code,model_name,
              message_count,tool_count,estimated_input_tokens,skill_codes_json,payload_json,payload_sha256,
              payload_expires_at,created_at)
            VALUES(#{item.runId},#{item.userId},#{item.requestSequence},#{item.requestStage},#{item.iterationNo},
              #{item.modelProviderCode},#{item.modelName},#{item.messageCount},#{item.toolCount},
              #{item.estimatedInputTokens},#{item.skillCodesJson},#{item.payloadJson},#{item.payloadSha256},
              #{item.payloadExpiresAt},#{item.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "item.id")
    int insertIgnore(@Param("item") AgentModelRequestSnapshot item);

    @Select("SELECT * FROM agent_model_request_snapshots WHERE run_id=#{runId} AND request_sequence=#{sequence} LIMIT 1")
    AgentModelRequestSnapshot findByRunAndSequence(@Param("runId") Long runId, @Param("sequence") Integer sequence);

    @Select("""
            SELECT * FROM agent_model_request_snapshots
            WHERE run_id = #{runId} AND (#{afterId} IS NULL OR id > #{afterId})
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<AgentModelRequestSnapshot> findByRun(@Param("runId") Long runId, @Param("afterId") Long afterId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM agent_model_request_snapshots WHERE run_id = #{runId}")
    long countByRun(@Param("runId") Long runId);

    @Update("""
            UPDATE agent_model_request_snapshots
            SET payload_json = NULL, payload_expired_at = #{now}
            WHERE payload_json IS NOT NULL AND payload_expires_at < #{now}
            LIMIT #{limit}
            """)
    int expirePayloads(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
