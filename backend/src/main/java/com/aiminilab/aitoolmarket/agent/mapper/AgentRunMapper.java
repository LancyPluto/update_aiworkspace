package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunListItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunStatsResponse;

public interface AgentRunMapper extends BaseMapper<AgentRun> {

    @Insert("""
            INSERT INTO agent_runs(session_id, user_id, status, intent, model_config_id, model_provider_code, model_name,
                                   estimated_credits, consumed_credits, error_code, error_message,
                                   started_at, finished_at, parent_run_id, source_user_message_id, context_snapshot_id, client_request_id,
                                   created_at, updated_at)
            VALUES(#{run.sessionId}, #{run.userId}, #{run.status}, #{run.intent}, #{run.modelConfigId}, #{run.modelProviderCode}, #{run.modelName},
                   #{run.estimatedCredits}, #{run.consumedCredits}, #{run.errorCode}, #{run.errorMessage},
                   #{run.startedAt}, #{run.finishedAt}, #{run.parentRunId}, #{run.sourceUserMessageId}, #{run.contextSnapshotId}, #{run.clientRequestId},
                   #{run.createdAt}, #{run.updatedAt})
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
                   r.error_message,
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
            ORDER BY r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdminAgentRunListItemResponse> findForAdmin(@Param("status") String status,
                                                     @Param("userId") Long userId,
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
            </script>
            """)
    long countForAdmin(@Param("status") String status, @Param("userId") Long userId);

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

    @Update("""
            UPDATE agent_runs
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                finished_at = #{now}, updated_at = #{now}
            WHERE id = #{runId}
              AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
            """)
    int markFailed(@Param("runId") Long runId,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_runs
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                consumed_credits = #{consumedCredits}, finished_at = #{now}, updated_at = #{now}
            WHERE id = #{runId}
              AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
            """)
    int markFailedWithConsumedCredits(@Param("runId") Long runId,
                                      @Param("errorCode") String errorCode,
                                      @Param("errorMessage") String errorMessage,
                                      @Param("consumedCredits") int consumedCredits,
                                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_runs
            SET status = 'CANCELLED', finished_at = #{now}, updated_at = #{now}
            WHERE id = #{runId}
              AND status IN ('CREATED', 'RUNNING', 'WAITING_USER_CONFIRMATION')
            """)
    int markCancelled(@Param("runId") Long runId, @Param("now") LocalDateTime now);
}
