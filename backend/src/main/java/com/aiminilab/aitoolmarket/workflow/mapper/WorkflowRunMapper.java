package com.aiminilab.aitoolmarket.workflow.mapper;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    @Insert("""
            INSERT INTO workflow_provider_cost_budget_days(budget_date, created_at, updated_at)
            VALUES (#{budgetDate}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE updated_at = updated_at
            """)
    int acquireProviderCostBudgetDayLock(@Param("budgetDate") LocalDate budgetDate);

    @Select("""
            SELECT /*+ INDEX(workflow_runs PRIMARY) */ provider_cost_reserved_cny
            FROM workflow_runs
            WHERE status IN ('RUNNING', 'AWAITING_USER', 'AWAITING_FUNDS', 'CANCELLING')
            ORDER BY id
            FOR UPDATE
            """)
    java.util.List<BigDecimal> selectActiveProviderCostReservationsForUpdate();

    @Select("""
            SELECT /*+ INDEX(workflow_runs PRIMARY) */ id
            FROM workflow_runs
            WHERE status IN ('RUNNING', 'AWAITING_USER', 'AWAITING_FUNDS', 'CANCELLING')
              AND (provider_cost_reserved_cny IS NULL OR provider_cost_reserved_cny <= 0)
            ORDER BY id
            FOR UPDATE
            """)
    java.util.List<Long> selectActiveRunsWithMissingProviderCostReservationForUpdate();

    @Select("SELECT * FROM workflow_runs WHERE root_task_id = #{rootTaskId} ORDER BY id DESC LIMIT 1")
    WorkflowRun selectByRootTaskId(@Param("rootTaskId") Long rootTaskId);

    @Select("SELECT * FROM workflow_runs WHERE root_task_id = #{rootTaskId} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    WorkflowRun selectByRootTaskIdForUpdate(@Param("rootTaskId") Long rootTaskId);

    @Select("SELECT * FROM workflow_runs WHERE id = #{runId} FOR UPDATE")
    WorkflowRun selectByIdForUpdate(@Param("runId") Long runId);

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
                error_message = #{errorMessage},
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
                error_message = NULL, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision} AND status = 'RUNNING'
            """)
    int markAwaitingUser(@Param("runId") Long runId,
                         @Param("expectedRevision") Long expectedRevision,
                         @Param("nodeId") String nodeId,
                         @Param("stepId") Long stepId);

    @Update("""
            UPDATE workflow_runs
            SET status = 'RUNNING', input_json = #{inputJson}, context_json = #{contextJson},
                current_node_id = #{nodeId}, current_step_id = NULL, error_message = NULL,
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
            SET status = 'RUNNING', billing_status = 'CLEAR', error_message = NULL,
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision}
              AND status = 'AWAITING_FUNDS' AND current_step_id = #{stepId}
            """)
    int resumeAwaitingFunds(@Param("runId") Long runId,
                            @Param("expectedRevision") Long expectedRevision,
                            @Param("stepId") Long stepId);

    @Update("""
            <script>
            UPDATE workflow_runs
            SET status = 'CANCELLING', cancellation_generation = cancellation_generation + 1,
                error_message = #{reason}, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND revision = #{expectedRevision} AND status IN
            <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
              #{status}
            </foreach>
            </script>
            """)
    int beginCancellation(@Param("runId") Long runId,
                          @Param("expectedRevision") Long expectedRevision,
                          @Param("expectedStatuses") java.util.List<String> expectedStatuses,
                          @Param("reason") String reason);

    @Update("""
            UPDATE workflow_runs
            SET status = 'CANCELLED', billing_status = 'CLEAR', finished_at = CURRENT_TIMESTAMP,
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

    @Update("""
            UPDATE workflow_runs
            SET status = #{nextStatus},
                context_json = #{contextJson},
                current_node_id = #{currentNodeId},
                error_message = #{errorMessage},
                finished_at = #{finishedAt},
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId}
              AND revision = #{expectedRevision}
              AND status = #{expectedStatus}
            """)
    int updateRunState(@Param("runId") Long runId,
                       @Param("expectedRevision") Long expectedRevision,
                       @Param("expectedStatus") String expectedStatus,
                       @Param("nextStatus") String nextStatus,
                       @Param("contextJson") String contextJson,
                       @Param("currentNodeId") String currentNodeId,
                       @Param("errorMessage") String errorMessage,
                       @Param("finishedAt") java.time.LocalDateTime finishedAt);

    @Update("""
            UPDATE workflow_runs
            SET status = #{nextStatus},
                input_json = #{inputJson},
                context_json = #{contextJson},
                current_node_id = #{currentNodeId},
                error_message = #{errorMessage},
                finished_at = #{finishedAt},
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId}
              AND revision = #{expectedRevision}
              AND status = #{expectedStatus}
            """)
    int updateRunStateWithInput(@Param("runId") Long runId,
                                @Param("expectedRevision") Long expectedRevision,
                                @Param("expectedStatus") String expectedStatus,
                                @Param("nextStatus") String nextStatus,
                                @Param("inputJson") String inputJson,
                                @Param("contextJson") String contextJson,
                                @Param("currentNodeId") String currentNodeId,
                                @Param("errorMessage") String errorMessage,
                                @Param("finishedAt") java.time.LocalDateTime finishedAt);
}
