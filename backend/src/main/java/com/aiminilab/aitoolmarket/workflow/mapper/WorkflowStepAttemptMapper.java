package com.aiminilab.aitoolmarket.workflow.mapper;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.support.WorkflowFailureContract;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface WorkflowStepAttemptMapper extends BaseMapper<WorkflowStepAttempt> {

    @Select("SELECT * FROM workflow_step_attempts WHERE id = #{attemptId} FOR UPDATE")
    WorkflowStepAttempt selectByIdForUpdate(@Param("attemptId") Long attemptId);

    @Select("SELECT * FROM workflow_step_attempts WHERE child_task_id = #{childTaskId} LIMIT 1")
    WorkflowStepAttempt selectByChildTaskId(@Param("childTaskId") Long childTaskId);

    @Select("""
            SELECT step.run_id
            FROM workflow_step_attempts attempt
            JOIN workflow_run_steps step ON step.id = attempt.step_id
            WHERE attempt.child_task_id = #{childTaskId}
            LIMIT 1
            """)
    Long selectRunIdByChildTaskId(@Param("childTaskId") Long childTaskId);

    @Select("SELECT * FROM workflow_step_attempts WHERE claim_token = #{claimToken} LIMIT 1")
    WorkflowStepAttempt selectByClaimToken(@Param("claimToken") String claimToken);

    @Select("SELECT provider_request_id FROM workflow_step_attempts WHERE id = #{attemptId}")
    String selectProviderRequestId(@Param("attemptId") Long attemptId);

    @Update("""
            UPDATE workflow_step_attempts
            SET provider_request_id = #{providerRequestId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId} AND provider_request_id IS NULL
            """)
    int attachProviderRequestId(@Param("attemptId") Long attemptId,
                                @Param("providerRequestId") String providerRequestId);

    @Select("""
            SELECT a.* FROM workflow_step_attempts a
            JOIN workflow_run_steps s ON s.id = a.step_id
            WHERE s.run_id = #{runId}
            ORDER BY a.id ASC
            """)
    List<WorkflowStepAttempt> selectByRunId(@Param("runId") Long runId);

    @Update("""
            UPDATE workflow_step_attempts
            SET child_task_id = #{childTaskId},
                status = 'DISPATCHED',
                lease_expires_at = #{leaseExpiresAt},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId}
              AND status = 'CREATED'
              AND child_task_id IS NULL
            """)
    int markDispatched(@Param("attemptId") Long attemptId,
                       @Param("childTaskId") Long childTaskId,
                       @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            <script>
            UPDATE workflow_step_attempts
            SET status = 'RUNNING',
                lease_expires_at = #{leaseExpiresAt},
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markRunning(@Param("attemptId") Long attemptId,
                    @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                    @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE workflow_step_attempts
            SET status = 'SUCCESS',
                output_json = #{outputJson},
                error_code = NULL,
                error_message = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                provider_request_id = COALESCE(provider_request_id, #{providerRequestId}),
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId}
              AND (#{providerRequestId} IS NULL
                   OR provider_request_id IS NULL
                   OR provider_request_id = #{providerRequestId})
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markSuccess(@Param("attemptId") Long attemptId,
                    @Param("outputJson") String outputJson,
                    @Param("providerRequestId") String providerRequestId,
                    @Param("expectedStatuses") List<String> expectedStatuses);

    default int markFailed(Long attemptId,
                           String errorCode,
                           String errorMessage,
                           String providerRequestId,
                           List<String> expectedStatuses) {
        WorkflowFailureContract failure = WorkflowFailureContract.from(errorCode, errorMessage);
        return markFailedWithContract(
                attemptId,
                failure.errorCode(),
                failure.userMessage(),
                failure.developerMessage(),
                failure.failureTraceId(),
                providerRequestId,
                expectedStatuses
        );
    }

    @Update("""
            <script>
            UPDATE workflow_step_attempts
            SET status = 'FAILED',
                error_code = #{errorCode},
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                provider_request_id = COALESCE(provider_request_id, #{providerRequestId}),
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId}
              AND (#{providerRequestId} IS NULL
                   OR provider_request_id IS NULL
                   OR provider_request_id = #{providerRequestId})
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markFailedWithContract(@Param("attemptId") Long attemptId,
                               @Param("errorCode") String errorCode,
                               @Param("userMessage") String userMessage,
                               @Param("developerMessage") String developerMessage,
                               @Param("failureTraceId") String failureTraceId,
                               @Param("providerRequestId") String providerRequestId,
                               @Param("expectedStatuses") List<String> expectedStatuses);

    @Select("""
            SELECT *
            FROM workflow_step_attempts
            WHERE status IN ('DISPATCHED', 'QUEUED', 'RUNNING')
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at < #{cutoff}
            ORDER BY lease_expires_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<WorkflowStepAttempt> selectExpiredActive(@Param("cutoff") LocalDateTime cutoff,
                                                   @Param("limit") int limit);

    default int markTimedOutIfExpired(Long attemptId, LocalDateTime cutoff) {
        WorkflowFailureContract failure = WorkflowFailureContract.from(
                "ATTEMPT_LEASE_EXPIRED",
                "Workflow step attempt lease expired before worker claim"
        );
        return markTimedOutIfExpiredWithContract(
                attemptId, cutoff, failure.userMessage(), failure.developerMessage(), failure.failureTraceId()
        );
    }

    @Update("""
            UPDATE workflow_step_attempts
            SET status = 'TIMEOUT',
                error_code = 'ATTEMPT_LEASE_EXPIRED',
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId}
              AND status IN ('DISPATCHED', 'QUEUED')
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at < #{cutoff}
            """)
    int markTimedOutIfExpiredWithContract(@Param("attemptId") Long attemptId,
                                          @Param("cutoff") LocalDateTime cutoff,
                                          @Param("userMessage") String userMessage,
                                          @Param("developerMessage") String developerMessage,
                                          @Param("failureTraceId") String failureTraceId);

    default int markLostIfExpired(Long attemptId, LocalDateTime cutoff) {
        WorkflowFailureContract failure = WorkflowFailureContract.from(
                "ATTEMPT_LEASE_EXPIRED",
                "Workflow step attempt lease expired"
        );
        return markLostIfExpiredWithContract(
                attemptId, cutoff, failure.userMessage(), failure.developerMessage(), failure.failureTraceId()
        );
    }

    @Update("""
            UPDATE workflow_step_attempts
            SET status = 'LOST',
                error_code = 'ATTEMPT_LEASE_EXPIRED',
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId}
              AND status = 'RUNNING'
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at < #{cutoff}
            """)
    int markLostIfExpiredWithContract(@Param("attemptId") Long attemptId,
                                      @Param("cutoff") LocalDateTime cutoff,
                                      @Param("userMessage") String userMessage,
                                      @Param("developerMessage") String developerMessage,
                                      @Param("failureTraceId") String failureTraceId);

    default int markLostForTerminalChild(Long attemptId, String errorMessage) {
        WorkflowFailureContract failure = WorkflowFailureContract.from("CHILD_ALREADY_TERMINAL", errorMessage);
        return markLostForTerminalChildWithContract(
                attemptId, failure.userMessage(), failure.developerMessage(), failure.failureTraceId()
        );
    }

    @Update("""
            UPDATE workflow_step_attempts
            SET status = 'LOST',
                error_code = 'CHILD_ALREADY_TERMINAL',
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId} AND status IN ('DISPATCHED', 'QUEUED', 'RUNNING')
            """)
    int markLostForTerminalChildWithContract(@Param("attemptId") Long attemptId,
                                             @Param("userMessage") String userMessage,
                                             @Param("developerMessage") String developerMessage,
                                             @Param("failureTraceId") String failureTraceId);

    default int cancelIfActive(Long attemptId, String reason) {
        WorkflowFailureContract failure = WorkflowFailureContract.from("WORKFLOW_CANCELLED", reason);
        return cancelIfActiveWithContract(
                attemptId, failure.userMessage(), failure.developerMessage(), failure.failureTraceId()
        );
    }

    @Update("""
            UPDATE workflow_step_attempts
            SET status = 'CANCELLED', error_code = 'WORKFLOW_CANCELLED',
                error_message = #{developerMessage}, user_message = #{userMessage},
                developer_message = #{developerMessage}, failure_trace_id = #{failureTraceId},
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{attemptId}
              AND status IN ('CREATED', 'DISPATCHED', 'QUEUED', 'RUNNING')
            """)
    int cancelIfActiveWithContract(@Param("attemptId") Long attemptId,
                                   @Param("userMessage") String userMessage,
                                   @Param("developerMessage") String developerMessage,
                                   @Param("failureTraceId") String failureTraceId);
}
