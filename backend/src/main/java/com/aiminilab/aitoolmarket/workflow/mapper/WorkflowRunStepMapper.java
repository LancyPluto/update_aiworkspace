package com.aiminilab.aitoolmarket.workflow.mapper;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.support.WorkflowFailureContract;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

public interface WorkflowRunStepMapper extends BaseMapper<WorkflowRunStep> {

    @Select("SELECT * FROM workflow_run_steps WHERE run_id = #{runId} ORDER BY id ASC")
    List<WorkflowRunStep> selectByRunId(@Param("runId") Long runId);

    @Select("SELECT * FROM workflow_run_steps WHERE task_id = #{taskId} LIMIT 1")
    WorkflowRunStep selectByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM workflow_run_steps WHERE id = #{stepId} FOR UPDATE")
    WorkflowRunStep selectByIdForUpdate(@Param("stepId") Long stepId);

    @Select("SELECT run_id FROM workflow_run_steps WHERE id = #{stepId}")
    Long selectRunIdById(@Param("stepId") Long stepId);

    default Optional<WorkflowRunStep> findByTaskId(Long taskId) {
        return Optional.ofNullable(selectByTaskId(taskId));
    }

    @Select("SELECT * FROM workflow_run_steps WHERE run_id = #{runId} AND node_id = #{nodeId} LIMIT 1")
    WorkflowRunStep selectByRunIdAndNodeId(@Param("runId") Long runId, @Param("nodeId") String nodeId);

    @Update("""
            UPDATE workflow_run_steps
            SET status = #{nextStatus},
                revision = revision + 1
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND status = #{expectedStatus}
            """)
    int casStatus(@Param("stepId") Long stepId,
                  @Param("expectedRevision") Long expectedRevision,
                  @Param("expectedStatus") String expectedStatus,
                  @Param("nextStatus") String nextStatus);

    @Update("""
            UPDATE workflow_run_steps
            SET status = 'AWAITING_USER', revision = revision + 1,
                output_json = #{previewJson}, error_code = NULL, error_message = NULL,
                user_message = NULL, developer_message = NULL, failure_trace_id = NULL,
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP)
            WHERE id = #{stepId} AND revision = #{expectedRevision} AND status = 'PENDING'
            """)
    int markAwaitingUser(@Param("stepId") Long stepId,
                         @Param("expectedRevision") Long expectedRevision,
                         @Param("previewJson") String previewJson);

    @Update("""
            UPDATE workflow_run_steps
            SET status = 'SUCCESS', revision = revision + 1, output_json = #{outputJson},
                error_code = NULL, error_message = NULL, user_message = NULL,
                developer_message = NULL, failure_trace_id = NULL,
                finished_at = CURRENT_TIMESTAMP
            WHERE id = #{stepId} AND revision = #{expectedRevision} AND status = 'AWAITING_USER'
            """)
    int completeAwaitingUser(@Param("stepId") Long stepId,
                             @Param("expectedRevision") Long expectedRevision,
                             @Param("outputJson") String outputJson);

    default int cancelActiveByRunId(Long runId, String reason) {
        WorkflowFailureContract failure = WorkflowFailureContract.from("WORKFLOW_CANCELLED", reason);
        return cancelActiveByRunIdWithContract(
                runId, failure.userMessage(), failure.developerMessage(), failure.failureTraceId()
        );
    }

    @Update("""
            UPDATE workflow_run_steps
            SET status = 'CANCELLED', revision = revision + 1,
                error_code = 'WORKFLOW_CANCELLED', error_message = #{developerMessage},
                user_message = #{userMessage}, developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId}, finished_at = CURRENT_TIMESTAMP
            WHERE run_id = #{runId}
              AND status IN ('PENDING', 'READY', 'QUEUED', 'RUNNING', 'AWAITING_USER', 'AWAITING_FUNDS')
            """)
    int cancelActiveByRunIdWithContract(@Param("runId") Long runId,
                                        @Param("userMessage") String userMessage,
                                        @Param("developerMessage") String developerMessage,
                                        @Param("failureTraceId") String failureTraceId);

    @Update("""
            UPDATE workflow_run_steps
            SET status = 'READY',
                input_json = #{inputJson},
                error_code = NULL,
                error_message = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                started_at = COALESCE(started_at, #{startedAt}),
                revision = revision + 1
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND status = #{expectedStatus}
              AND ((#{expectedCurrentAttemptId} IS NULL AND current_attempt_id IS NULL)
                   OR current_attempt_id = #{expectedCurrentAttemptId})
            """)
    int markReadyForDispatch(@Param("stepId") Long stepId,
                             @Param("expectedRevision") Long expectedRevision,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("expectedCurrentAttemptId") Long expectedCurrentAttemptId,
                             @Param("inputJson") String inputJson,
                             @Param("startedAt") java.time.LocalDateTime startedAt);

    @Update("""
            UPDATE workflow_run_steps
            SET status = 'QUEUED',
                revision = revision + 1,
                task_id = #{childTaskId},
                attempt = #{attemptNo},
                attempt_count = #{attemptNo},
                current_attempt_id = #{attemptId},
                error_code = NULL,
                error_message = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                finished_at = NULL
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND status = 'READY'
              AND ((#{expectedCurrentAttemptId} IS NULL AND current_attempt_id IS NULL)
                   OR current_attempt_id = #{expectedCurrentAttemptId})
            """)
    int activateAttempt(@Param("stepId") Long stepId,
                        @Param("expectedRevision") Long expectedRevision,
                        @Param("expectedCurrentAttemptId") Long expectedCurrentAttemptId,
                        @Param("attemptId") Long attemptId,
                        @Param("attemptNo") Integer attemptNo,
                        @Param("childTaskId") Long childTaskId);

    @Update("""
            UPDATE workflow_run_steps
            SET status = 'RUNNING',
                revision = revision + 1
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND status = 'QUEUED'
              AND current_attempt_id = #{attemptId}
            """)
    int markActiveAttemptRunning(@Param("stepId") Long stepId,
                                 @Param("expectedRevision") Long expectedRevision,
                                 @Param("attemptId") Long attemptId);

    @Update("""
            <script>
            UPDATE workflow_run_steps
            SET status = 'SUCCESS',
                revision = revision + 1,
                output_json = #{outputJson},
                error_code = NULL,
                error_message = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                finished_at = CURRENT_TIMESTAMP
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND current_attempt_id = #{attemptId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int completeActiveAttempt(@Param("stepId") Long stepId,
                              @Param("expectedRevision") Long expectedRevision,
                              @Param("attemptId") Long attemptId,
                              @Param("outputJson") String outputJson,
                              @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            UPDATE workflow_run_steps
            SET status = 'AWAITING_FUNDS',
                revision = revision + 1,
                output_json = #{outputJson},
                error_code = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                error_message = #{errorMessage},
                finished_at = NULL
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND current_attempt_id = #{attemptId}
              AND status = 'RUNNING'
            """)
    int markActiveAttemptAwaitingFunds(@Param("stepId") Long stepId,
                                       @Param("expectedRevision") Long expectedRevision,
                                       @Param("attemptId") Long attemptId,
                                       @Param("outputJson") String outputJson,
                                       @Param("errorMessage") String errorMessage);

    @Update("""
            <script>
            UPDATE workflow_run_steps
            SET status = 'READY',
                revision = revision + 1,
                error_code = NULL,
                error_message = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                finished_at = NULL
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND current_attempt_id = #{attemptId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int releaseActiveAttemptForRetry(@Param("stepId") Long stepId,
                                     @Param("expectedRevision") Long expectedRevision,
                                     @Param("attemptId") Long attemptId,
                                     @Param("errorMessage") String errorMessage,
                                     @Param("expectedStatuses") List<String> expectedStatuses);

    default int failActiveAttempt(Long stepId,
                                  Long expectedRevision,
                                  Long attemptId,
                                  String errorMessage,
                                  List<String> expectedStatuses) {
        WorkflowFailureContract failure = WorkflowFailureContract.from("WORKFLOW_FAILED", errorMessage);
        return failActiveAttemptWithContract(
                stepId,
                expectedRevision,
                attemptId,
                failure.errorCode(),
                failure.userMessage(),
                failure.developerMessage(),
                failure.failureTraceId(),
                expectedStatuses
        );
    }

    @Update("""
            <script>
            UPDATE workflow_run_steps
            SET status = 'FAILED',
                revision = revision + 1,
                error_code = #{errorCode},
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                finished_at = CURRENT_TIMESTAMP
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND current_attempt_id = #{attemptId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int failActiveAttemptWithContract(@Param("stepId") Long stepId,
                                      @Param("expectedRevision") Long expectedRevision,
                                      @Param("attemptId") Long attemptId,
                                      @Param("errorCode") String errorCode,
                                      @Param("userMessage") String userMessage,
                                      @Param("developerMessage") String developerMessage,
                                      @Param("failureTraceId") String failureTraceId,
                                      @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            UPDATE workflow_run_steps
            SET status = 'SUCCESS',
                revision = revision + 1,
                output_json = #{outputJson},
                error_code = NULL,
                error_message = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                finished_at = #{finishedAt}
            WHERE id = #{stepId}
              AND revision = #{expectedRevision}
              AND status = #{expectedStatus}
              AND ((#{expectedCurrentAttemptId} IS NULL AND current_attempt_id IS NULL)
                   OR current_attempt_id = #{expectedCurrentAttemptId})
            """)
    int completeInlineStep(@Param("stepId") Long stepId,
                           @Param("expectedRevision") Long expectedRevision,
                           @Param("expectedStatus") String expectedStatus,
                           @Param("expectedCurrentAttemptId") Long expectedCurrentAttemptId,
                           @Param("outputJson") String outputJson,
                           @Param("finishedAt") java.time.LocalDateTime finishedAt);
}
