package com.aiminilab.aitoolmarket.workflow.mapper;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.support.WorkflowFailureContract;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    @Select("SELECT * FROM workflow_runs WHERE root_task_id = #{rootTaskId} ORDER BY id DESC LIMIT 1")
    WorkflowRun selectByRootTaskId(@Param("rootTaskId") Long rootTaskId);

    @Select("SELECT * FROM workflow_runs WHERE root_task_id = #{rootTaskId} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    WorkflowRun selectByRootTaskIdForUpdate(@Param("rootTaskId") Long rootTaskId);

    @Select("SELECT * FROM workflow_runs WHERE id = #{runId} FOR UPDATE")
    WorkflowRun selectByIdForUpdate(@Param("runId") Long runId);

    @Update("""
            UPDATE workflow_runs
            SET billing_status = 'RECONCILIATION_FAILED',
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId}
              AND revision = #{expectedRevision}
              AND billing_status <> 'RECONCILIATION_FAILED'
            """)
    int markBillingReconciliationFailed(@Param("runId") Long runId,
                                        @Param("expectedRevision") Long expectedRevision);

    @Update("""
            UPDATE workflow_runs
            SET status = 'CANCELLING',
                billing_status = 'RECONCILIATION_FAILED',
                cancellation_generation = cancellation_generation + 1,
                error_code = 'WORKFLOW_BILLING_RECONCILIATION_FAILED',
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId}
              AND revision = #{expectedRevision}
              AND status IN ('RUNNING', 'AWAITING_USER', 'AWAITING_FUNDS')
            """)
    int beginBillingReconciliationIsolation(@Param("runId") Long runId,
                                            @Param("expectedRevision") Long expectedRevision,
                                            @Param("userMessage") String userMessage,
                                            @Param("developerMessage") String developerMessage,
                                            @Param("failureTraceId") String failureTraceId);

    default Optional<WorkflowRun> findByRootTaskId(Long rootTaskId) {
        return Optional.ofNullable(selectByRootTaskId(rootTaskId));
    }

    @Select("""
            SELECT * FROM workflow_runs
            WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}
            LIMIT 1
            """)
    WorkflowRun selectByUserAndClientRequestId(@Param("userId") Long userId,
                                                @Param("clientRequestId") String clientRequestId);

    @Select("""
            SELECT * FROM workflow_runs
            WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}
            LIMIT 1
            """)
    WorkflowRun selectByUserAndClientRequestIdAfterAdmissionLock(
            @Param("userId") Long userId,
            @Param("clientRequestId") String clientRequestId
    );

    @Update("""
            UPDATE workflow_runs
            SET status = #{nextStatus},
                error_message = #{errorMessage},
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId}
              AND revision = #{expectedRevision}
              AND status = #{expectedStatus}
            """)
    int casStatus(@Param("runId") Long runId,
                  @Param("expectedRevision") Long expectedRevision,
                  @Param("expectedStatus") String expectedStatus,
                  @Param("nextStatus") String nextStatus,
                  @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE workflow_runs
            SET status = 'AWAITING_FUNDS',
                billing_status = 'AWAITING_FUNDS',
                current_step_id = #{stepId},
                error_code = NULL,
                error_message = #{errorMessage},
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId}
              AND revision = #{expectedRevision}
              AND status = 'RUNNING'
            """)
    int markAwaitingFunds(@Param("runId") Long runId,
                          @Param("expectedRevision") Long expectedRevision,
                          @Param("stepId") Long stepId,
                          @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE workflow_runs
            SET status = 'AWAITING_USER', current_node_id = #{nodeId}, current_step_id = #{stepId},
                error_code = NULL, error_message = NULL, user_message = NULL,
                developer_message = NULL, failure_trace_id = NULL,
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision} AND status = 'RUNNING'
            """)
    int markAwaitingUser(@Param("runId") Long runId,
                         @Param("expectedRevision") Long expectedRevision,
                         @Param("nodeId") String nodeId,
                         @Param("stepId") Long stepId);

    @Update("""
            UPDATE workflow_runs
            SET status = 'RUNNING', input_json = #{inputJson}, context_json = #{contextJson},
                current_node_id = #{nodeId}, current_step_id = NULL,
                error_code = NULL, error_message = NULL, user_message = NULL,
                developer_message = NULL, failure_trace_id = NULL,
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision}
              AND status = 'AWAITING_USER' AND current_step_id = #{stepId}
            """)
    int resumeAwaitingUser(@Param("runId") Long runId,
                           @Param("expectedRevision") Long expectedRevision,
                           @Param("stepId") Long stepId,
                           @Param("nodeId") String nodeId,
                           @Param("inputJson") String inputJson,
                           @Param("contextJson") String contextJson);

    @Update("""
            UPDATE workflow_runs
            SET status = 'RUNNING', billing_status = 'CLEAR',
                error_code = NULL, error_message = NULL, user_message = NULL,
                developer_message = NULL, failure_trace_id = NULL,
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision}
              AND status = 'AWAITING_FUNDS' AND current_step_id = #{stepId}
            """)
    int resumeAwaitingFunds(@Param("runId") Long runId,
                            @Param("expectedRevision") Long expectedRevision,
                            @Param("stepId") Long stepId);

    default int beginCancellation(Long runId,
                                  Long expectedRevision,
                                  java.util.List<String> expectedStatuses,
                                  String reason) {
        WorkflowFailureContract failure = WorkflowFailureContract.from("WORKFLOW_CANCELLED", reason);
        return beginCancellationWithContract(
                runId,
                expectedRevision,
                expectedStatuses,
                failure.userMessage(),
                failure.developerMessage(),
                failure.failureTraceId()
        );
    }

    @Update("""
            <script>
            UPDATE workflow_runs
            SET status = 'CANCELLING', cancellation_generation = cancellation_generation + 1,
                error_code = 'WORKFLOW_CANCELLED', error_message = #{developerMessage},
                user_message = #{userMessage}, developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId}, revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision} AND status IN
            <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
              #{status}
            </foreach>
            </script>
            """)
    int beginCancellationWithContract(@Param("runId") Long runId,
                                      @Param("expectedRevision") Long expectedRevision,
                                      @Param("expectedStatuses") java.util.List<String> expectedStatuses,
                                      @Param("userMessage") String userMessage,
                                      @Param("developerMessage") String developerMessage,
                                      @Param("failureTraceId") String failureTraceId);

    @Update("""
            UPDATE workflow_runs
            SET status = 'CANCELLED',
                billing_status = CASE
                    WHEN billing_status = 'RECONCILIATION_FAILED' THEN billing_status
                    ELSE 'CLEAR'
                END,
                finished_at = CURRENT_TIMESTAMP,
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision} AND status = 'CANCELLING'
            """)
    int finishCancellation(@Param("runId") Long runId,
                           @Param("expectedRevision") Long expectedRevision);

    @Update("""
            UPDATE workflow_runs
            SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision} AND status = 'CANCELLING'
            """)
    int deferCancellationReconciliation(@Param("runId") Long runId,
                                        @Param("expectedRevision") Long expectedRevision);

    default int updateRunState(Long runId,
                               Long expectedRevision,
                               String expectedStatus,
                               String nextStatus,
                               String contextJson,
                               String currentNodeId,
                               String errorMessage,
                               java.time.LocalDateTime finishedAt) {
        WorkflowFailureContract failure = "FAILED".equals(nextStatus)
                ? WorkflowFailureContract.from("WORKFLOW_FAILED", errorMessage)
                : null;
        return updateRunStateWithContract(
                runId,
                expectedRevision,
                expectedStatus,
                nextStatus,
                contextJson,
                currentNodeId,
                failure == null ? null : failure.errorCode(),
                failure == null ? null : failure.userMessage(),
                failure == null ? null : failure.developerMessage(),
                failure == null ? null : failure.failureTraceId(),
                finishedAt
        );
    }

    @Update("""
            UPDATE workflow_runs
            SET status = #{nextStatus},
                context_json = #{contextJson},
                current_node_id = #{currentNodeId},
                error_code = #{errorCode},
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                finished_at = #{finishedAt},
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId}
              AND revision = #{expectedRevision}
              AND status = #{expectedStatus}
            """)
    int updateRunStateWithContract(@Param("runId") Long runId,
                                   @Param("expectedRevision") Long expectedRevision,
                                   @Param("expectedStatus") String expectedStatus,
                                   @Param("nextStatus") String nextStatus,
                                   @Param("contextJson") String contextJson,
                                   @Param("currentNodeId") String currentNodeId,
                                   @Param("errorCode") String errorCode,
                                   @Param("userMessage") String userMessage,
                                   @Param("developerMessage") String developerMessage,
                                   @Param("failureTraceId") String failureTraceId,
                                   @Param("finishedAt") java.time.LocalDateTime finishedAt);

    default int updateRunStateWithInput(Long runId,
                                        Long expectedRevision,
                                        String expectedStatus,
                                        String nextStatus,
                                        String inputJson,
                                        String contextJson,
                                        String currentNodeId,
                                        String errorMessage,
                                        java.time.LocalDateTime finishedAt) {
        WorkflowFailureContract failure = "FAILED".equals(nextStatus)
                ? WorkflowFailureContract.from("WORKFLOW_FAILED", errorMessage)
                : null;
        return updateRunStateWithInputContract(
                runId,
                expectedRevision,
                expectedStatus,
                nextStatus,
                inputJson,
                contextJson,
                currentNodeId,
                failure == null ? null : failure.errorCode(),
                failure == null ? null : failure.userMessage(),
                failure == null ? null : failure.developerMessage(),
                failure == null ? null : failure.failureTraceId(),
                finishedAt
        );
    }

    @Update("""
            UPDATE workflow_runs
            SET status = #{nextStatus},
                input_json = #{inputJson},
                context_json = #{contextJson},
                current_node_id = #{currentNodeId},
                error_code = #{errorCode},
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                finished_at = #{finishedAt},
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId}
              AND revision = #{expectedRevision}
              AND status = #{expectedStatus}
            """)
    int updateRunStateWithInputContract(@Param("runId") Long runId,
                                        @Param("expectedRevision") Long expectedRevision,
                                        @Param("expectedStatus") String expectedStatus,
                                        @Param("nextStatus") String nextStatus,
                                        @Param("inputJson") String inputJson,
                                        @Param("contextJson") String contextJson,
                                        @Param("currentNodeId") String currentNodeId,
                                        @Param("errorCode") String errorCode,
                                        @Param("userMessage") String userMessage,
                                        @Param("developerMessage") String developerMessage,
                                        @Param("failureTraceId") String failureTraceId,
                                        @Param("finishedAt") java.time.LocalDateTime finishedAt);
}
