package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.support.AgentFailureMessage;
import com.aiminilab.aitoolmarket.common.error.ErrorMessageSanitizer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.slf4j.MDC;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunListItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunStatsResponse;

public interface AgentRunMapper extends BaseMapper<AgentRun> {

    @Update("""
            INSERT INTO agent_run_execution_leases(run_id, owner_token, lease_expires_at, created_at, updated_at)
            VALUES(#{runId}, #{ownerToken}, #{expiresAt}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE
              owner_token = IF(owner_token = #{ownerToken} OR lease_expires_at <= #{now}, #{ownerToken}, owner_token),
              lease_expires_at = IF(owner_token = #{ownerToken} OR lease_expires_at <= #{now}, #{expiresAt}, lease_expires_at),
              updated_at = IF(owner_token = #{ownerToken} OR lease_expires_at <= #{now}, #{now}, updated_at)
            """)
    void acquireExecutionLease(@Param("runId") Long runId, @Param("ownerToken") String ownerToken,
                               @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);

    @Select("SELECT owner_token FROM agent_run_execution_leases WHERE run_id=#{runId}")
    String selectExecutionLeaseOwner(@Param("runId") Long runId);

    @Update("UPDATE agent_run_execution_leases SET lease_expires_at=#{expiresAt}, updated_at=#{now} WHERE run_id=#{runId} AND owner_token=#{ownerToken}")
    int renewExecutionLease(@Param("runId") Long runId, @Param("ownerToken") String ownerToken,
                             @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);

    @Update("DELETE FROM agent_run_execution_leases WHERE run_id=#{runId} AND owner_token=#{ownerToken}")
    int releaseExecutionLease(@Param("runId") Long runId, @Param("ownerToken") String ownerToken);

    @Select("""
            SELECT id FROM agent_runs
            WHERE status IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
              AND finished_at IS NOT NULL AND finished_at < #{cutoff}
              AND EXISTS (SELECT 1 FROM agent_langgraph_checkpoints c WHERE c.run_id = agent_runs.id)
            ORDER BY id LIMIT #{limit}
            """)
    List<Long> findExpiredLangGraphCheckpointRuns(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("<script>DELETE FROM agent_langgraph_checkpoint_writes WHERE run_id IN <foreach item='id' collection='runIds' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteExpiredLangGraphCheckpointWrites(@Param("runIds") List<Long> runIds);

    @Delete("<script>DELETE FROM agent_langgraph_checkpoints WHERE run_id IN <foreach item='id' collection='runIds' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteExpiredLangGraphCheckpoints(@Param("runIds") List<Long> runIds);

    @Update("""
            INSERT INTO agent_langgraph_checkpoints
              (run_id, thread_id, checkpoint_ns, checkpoint_id, parent_checkpoint_id, checkpoint_json, metadata_json,
               created_at, updated_at)
            VALUES (#{runId}, #{threadId}, #{checkpointNs}, #{checkpointId}, #{parentCheckpointId}, #{checkpointJson}, #{metadataJson},
                    #{now}, #{now})
            ON DUPLICATE KEY UPDATE
              parent_checkpoint_id = VALUES(parent_checkpoint_id),
              checkpoint_json = VALUES(checkpoint_json),
              metadata_json = VALUES(metadata_json),
              updated_at = VALUES(updated_at)
            """)
    void upsertLangGraphCheckpoint(@Param("runId") Long runId,
                                   @Param("threadId") String threadId,
                                   @Param("checkpointNs") String checkpointNs,
                                   @Param("checkpointId") String checkpointId,
                                   @Param("checkpointJson") String checkpointJson,
                                   @Param("metadataJson") String metadataJson,
                                   @Param("parentCheckpointId") String parentCheckpointId,
                                   @Param("now") LocalDateTime now);

    @Select("""
            SELECT run_id, thread_id, checkpoint_ns, checkpoint_id, parent_checkpoint_id, checkpoint_json, metadata_json
            FROM agent_langgraph_checkpoints
            WHERE run_id = #{runId} AND thread_id = #{threadId} AND checkpoint_ns = #{checkpointNs}
              AND (#{checkpointId} IS NULL OR checkpoint_id = #{checkpointId})
            ORDER BY id DESC LIMIT 1
            """)
    java.util.Map<String, Object> selectLangGraphCheckpoint(@Param("runId") Long runId,
                                                             @Param("threadId") String threadId,
                                                             @Param("checkpointNs") String checkpointNs,
                                                             @Param("checkpointId") String checkpointId);

    @Insert("""
            INSERT INTO agent_langgraph_checkpoint_writes
              (run_id, thread_id, checkpoint_ns, checkpoint_id, task_id, task_path, write_index, channel_name, value_type, value_base64, created_at)
            VALUES (#{runId}, #{threadId}, #{checkpointNs}, #{checkpointId}, #{taskId}, #{taskPath}, #{writeIndex}, #{channelName}, #{valueType}, #{valueBase64}, #{now})
            ON DUPLICATE KEY UPDATE channel_name=VALUES(channel_name), value_type=VALUES(value_type), value_base64=VALUES(value_base64), created_at=VALUES(created_at)
            """)
    void upsertLangGraphCheckpointWrite(@Param("runId") Long runId, @Param("threadId") String threadId,
        @Param("checkpointNs") String checkpointNs, @Param("checkpointId") String checkpointId, @Param("taskId") String taskId,
        @Param("taskPath") String taskPath, @Param("writeIndex") Integer writeIndex, @Param("channelName") String channelName,
        @Param("valueType") String valueType, @Param("valueBase64") String valueBase64, @Param("now") LocalDateTime now);

    @Select("""
            SELECT task_id, task_path, write_index, channel_name, value_type, value_base64
            FROM agent_langgraph_checkpoint_writes WHERE run_id=#{runId} AND thread_id=#{threadId}
              AND checkpoint_ns=#{checkpointNs} AND checkpoint_id=#{checkpointId} ORDER BY task_id, write_index
            """)
    List<java.util.Map<String, Object>> selectLangGraphCheckpointWrites(@Param("runId") Long runId, @Param("threadId") String threadId,
        @Param("checkpointNs") String checkpointNs, @Param("checkpointId") String checkpointId);

    @Select("""
            SELECT run_id, thread_id, checkpoint_ns, checkpoint_id, parent_checkpoint_id, checkpoint_json, metadata_json
            FROM agent_langgraph_checkpoints WHERE run_id=#{runId} AND thread_id=#{threadId} AND checkpoint_ns=#{checkpointNs}
              AND (#{beforeCheckpointId} IS NULL OR id < (SELECT id FROM agent_langgraph_checkpoints x WHERE x.run_id=#{runId} AND x.thread_id=#{threadId} AND x.checkpoint_ns=#{checkpointNs} AND x.checkpoint_id=#{beforeCheckpointId} LIMIT 1))
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<java.util.Map<String, Object>> listLangGraphCheckpoints(@Param("runId") Long runId, @Param("threadId") String threadId,
        @Param("checkpointNs") String checkpointNs, @Param("beforeCheckpointId") String beforeCheckpointId, @Param("limit") int limit);

    @Update("""
            DELETE FROM agent_langgraph_checkpoints
            WHERE run_id = #{runId} AND thread_id = #{threadId}
            """)
    void deleteLangGraphCheckpoint(@Param("runId") Long runId, @Param("threadId") String threadId);

    @Update("DELETE FROM agent_langgraph_checkpoint_writes WHERE run_id=#{runId} AND thread_id=#{threadId}")
    void deleteLangGraphCheckpointWrites(@Param("runId") Long runId, @Param("threadId") String threadId);

    @Insert("""
            INSERT INTO agent_runs(session_id, user_id, status, intent, model_config_id, model_provider_code, model_name,
                                   estimated_credits, consumed_credits, error_code, error_message,
                                   user_message, developer_message, failure_trace_id,
                                   started_at, finished_at, parent_run_id, source_user_message_id, context_snapshot_id, client_request_id,
                                   preferred_tool_code, created_at, updated_at)
            VALUES(#{run.sessionId}, #{run.userId}, #{run.status}, #{run.intent}, #{run.modelConfigId}, #{run.modelProviderCode}, #{run.modelName},
                   #{run.estimatedCredits}, #{run.consumedCredits}, #{run.errorCode}, #{run.errorMessage},
                   #{run.userMessage}, #{run.developerMessage}, #{run.failureTraceId},
                   #{run.startedAt}, #{run.finishedAt}, #{run.parentRunId}, #{run.sourceUserMessageId}, #{run.contextSnapshotId}, #{run.clientRequestId},
                   #{run.preferredToolCode}, #{run.createdAt}, #{run.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "run.id")
    void insertRun(@Param("run") AgentRun run);

    @Select("""
            SELECT *
            FROM agent_runs
            WHERE id = #{runId} AND user_id = #{userId}
            LIMIT 1
            """)
    AgentRun selectByIdAndUserId(@Param("runId") Long runId, @Param("userId") Long userId);

    default Optional<AgentRun> findByIdAndUserId(Long runId, Long userId) {
        return Optional.ofNullable(selectByIdAndUserId(runId, userId));
    }

    @Select("""
            SELECT *
            FROM agent_runs
            WHERE id = #{runId}
            LIMIT 1
            """)
    AgentRun selectDetailById(@Param("runId") Long runId);

    default Optional<AgentRun> findById(Long runId) {
        return Optional.ofNullable(selectDetailById(runId));
    }

    @Select("""
            <script>
            SELECT r.id,
                   r.session_id,
                   r.user_id,
                   r.status,
                   r.intent,
                   r.model_provider_code,
                   r.model_name,
                   r.estimated_credits,
                   r.consumed_credits,
                   r.error_code,
                   COALESCE(r.developer_message, r.error_message) AS error_message,
                   (SELECT COUNT(*) FROM agent_run_events e WHERE e.run_id = r.id) AS event_count,
                   (SELECT COUNT(*) FROM agent_tool_calls c WHERE c.run_id = r.id) AS tool_call_count,
                   r.started_at,
                   r.finished_at,
                   r.created_at,
                   r.updated_at
            FROM agent_runs r
            WHERE 1 = 1
            <if test="status != null and status.trim() != ''">
              AND r.status = #{status}
            </if>
            <if test="userId != null">
              AND r.user_id = #{userId}
            </if>
            <if test="taskId != null">
              AND EXISTS (
                SELECT 1
                FROM agent_tool_calls c
                WHERE c.run_id = r.id
                  AND c.task_id = #{taskId}
              )
            </if>
            ORDER BY r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdminAgentRunListItemResponse> findForAdmin(@Param("status") String status,
                                                     @Param("userId") Long userId,
                                                     @Param("taskId") Long taskId,
                                                     @Param("limit") int limit,
                                                     @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM agent_runs r
            WHERE 1 = 1
            <if test="status != null and status.trim() != ''">
              AND r.status = #{status}
            </if>
            <if test="userId != null">
              AND r.user_id = #{userId}
            </if>
            <if test="taskId != null">
              AND EXISTS (
                SELECT 1
                FROM agent_tool_calls c
                WHERE c.run_id = r.id
                  AND c.task_id = #{taskId}
              )
            </if>
            </script>
            """)
    long countForAdmin(@Param("status") String status, @Param("userId") Long userId, @Param("taskId") Long taskId);

    @Select("""
            SELECT
              COUNT(*) AS total_runs,
              COALESCE(SUM(CASE WHEN status IN ('CREATED', 'RUNNING', 'WAITING_USER_CONFIRMATION') THEN 1 ELSE 0 END), 0) AS active_runs,
              COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_runs,
              COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_runs,
              COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled_runs,
              (SELECT COUNT(*) FROM agent_tool_calls) AS tool_calls,
              COALESCE(SUM(consumed_credits), 0) AS total_consumed_credits
            FROM agent_runs
            """)
    AdminAgentRunStatsResponse statsForAdmin();

    @Select("""
            SELECT COUNT(*)
            FROM agent_runs
            WHERE user_id = #{userId}
              AND status IN ('CREATED', 'RUNNING', 'WAITING_USER_CONFIRMATION')
            """)
    long countActiveRuns(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM agent_runs
            WHERE session_id = #{sessionId}
              AND status IN ('CREATED', 'RUNNING', 'WAITING_USER_CONFIRMATION')
            """)
    long countActiveRunsBySession(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT *
            FROM agent_runs
            WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}
            LIMIT 1
            """)
    AgentRun selectByUserIdAndClientRequestId(@Param("userId") Long userId,
                                              @Param("clientRequestId") String clientRequestId);

    default Optional<AgentRun> findByUserIdAndClientRequestId(Long userId, String clientRequestId) {
        return Optional.ofNullable(selectByUserIdAndClientRequestId(userId, clientRequestId));
    }

    @Update("""
            UPDATE agent_runs
            SET status = 'RUNNING', started_at = #{now}, updated_at = #{now}
            WHERE id = #{runId}
            """)
    void markRunning(@Param("runId") Long runId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_runs
            SET status = 'RUNNING', started_at = COALESCE(started_at, #{now}), updated_at = #{now}
            WHERE id = #{runId}
              AND status = #{expectedStatus}
            """)
    int markRunningIfStatus(@Param("runId") Long runId,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_runs
            SET status = 'WAITING_USER_CONFIRMATION', updated_at = #{now}
            WHERE id = #{runId}
              AND status = #{expectedStatus}
            """)
    int markWaitingForConfirmationIfStatus(@Param("runId") Long runId,
                                           @Param("expectedStatus") String expectedStatus,
                                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_runs
            SET model_config_id = #{modelConfigId}, model_provider_code = #{modelProviderCode}, model_name = #{modelName}, updated_at = #{now}
            WHERE id = #{runId}
            """)
    void updateModel(@Param("runId") Long runId,
                     @Param("modelConfigId") Long modelConfigId,
                     @Param("modelProviderCode") String modelProviderCode,
                     @Param("modelName") String modelName,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_runs
            SET context_snapshot_id = #{contextSnapshotId}, updated_at = #{now}
            WHERE id = #{runId}
            """)
    void updateContextSnapshot(@Param("runId") Long runId,
                               @Param("contextSnapshotId") Long contextSnapshotId,
                               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_runs
            SET graph_checkpoint_json = #{checkpointJson}, updated_at = #{now}
            WHERE id = #{runId}
            """)
    void updateGraphCheckpoint(@Param("runId") Long runId,
                               @Param("checkpointJson") String checkpointJson,
                               @Param("now") LocalDateTime now);

    @Select("""
            SELECT graph_checkpoint_json
            FROM agent_runs
            WHERE id = #{runId}
            LIMIT 1
            """)
    String selectGraphCheckpoint(@Param("runId") Long runId);

    @Update("""
            UPDATE agent_runs
            SET status = 'SUCCESS', intent = #{intent}, model_provider_code = #{modelProviderCode},
                model_name = #{modelName}, consumed_credits = #{consumedCredits},
                finished_at = #{now}, updated_at = #{now}
            WHERE id = #{runId}
              AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
            """)
    int markSuccess(@Param("runId") Long runId,
                    @Param("intent") String intent,
                    @Param("modelProviderCode") String modelProviderCode,
                    @Param("modelName") String modelName,
                    @Param("consumedCredits") int consumedCredits,
                    @Param("now") LocalDateTime now);

    default int markFailed(Long runId,
                           String errorCode,
                           String errorMessage,
                           LocalDateTime now) {
        String userMessage = ErrorMessageSanitizer.sanitizeUserMessage(
                AgentFailureMessage.userMessage(errorCode),
                "Agent 执行失败，请稍后重试"
        );
        String developerMessage = ErrorMessageSanitizer.sanitizeDeveloperMessage(
                errorMessage,
                "Agent run failed"
        );
        return markFailedWithContract(runId, errorCode, userMessage, developerMessage, currentTraceId(), now);
    }

    @Update("""
            UPDATE agent_runs
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{developerMessage},
                user_message = #{userMessage}, developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                finished_at = #{now}, updated_at = #{now}
            WHERE id = #{runId}
              AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
            """)
    int markFailedWithContract(@Param("runId") Long runId,
                               @Param("errorCode") String errorCode,
                               @Param("userMessage") String userMessage,
                               @Param("developerMessage") String developerMessage,
                               @Param("failureTraceId") String failureTraceId,
                               @Param("now") LocalDateTime now);

    default int markFailedWithConsumedCredits(Long runId,
                                              String errorCode,
                                              String errorMessage,
                                              int consumedCredits,
                                              LocalDateTime now) {
        String userMessage = ErrorMessageSanitizer.sanitizeUserMessage(
                AgentFailureMessage.userMessage(errorCode),
                "Agent 执行失败，请稍后重试"
        );
        String developerMessage = ErrorMessageSanitizer.sanitizeDeveloperMessage(
                errorMessage,
                "Agent run failed"
        );
        return markFailedWithConsumedCreditsContract(
                runId, errorCode, userMessage, developerMessage, currentTraceId(), consumedCredits, now
        );
    }

    @Update("""
            UPDATE agent_runs
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{developerMessage},
                user_message = #{userMessage}, developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                consumed_credits = #{consumedCredits}, finished_at = #{now}, updated_at = #{now}
            WHERE id = #{runId}
              AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
            """)
    int markFailedWithConsumedCreditsContract(@Param("runId") Long runId,
                                              @Param("errorCode") String errorCode,
                                              @Param("userMessage") String userMessage,
                                              @Param("developerMessage") String developerMessage,
                                              @Param("failureTraceId") String failureTraceId,
                                              @Param("consumedCredits") int consumedCredits,
                                              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_runs
            SET status = 'CANCELLED', finished_at = #{now}, updated_at = #{now}
            WHERE id = #{runId}
              AND status IN ('CREATED', 'RUNNING', 'WAITING_USER_CONFIRMATION')
            """)
    int markCancelled(@Param("runId") Long runId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT *
            FROM agent_runs
            WHERE status IN ('CREATED', 'RUNNING', 'WAITING_USER_CONFIRMATION')
              AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<AgentRun> findStaleActiveRuns(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        String normalized = traceId.strip();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
