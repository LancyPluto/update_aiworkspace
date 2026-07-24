package com.aiminilab.aitoolmarket.task.routing.mapper;

import com.aiminilab.aitoolmarket.task.dto.RouteFailoverRequest;
import com.aiminilab.aitoolmarket.task.routing.entity.TaskModelRouteAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TaskModelRouteAttemptMapper extends BaseMapper<TaskModelRouteAttempt> {

    @Insert("""
            INSERT INTO task_model_route_attempts(
                task_id, attempt_no, model_config_id, vendor_account_id, status,
                claim_token, started_at, created_at, updated_at
            ) VALUES(
                #{attempt.taskId}, #{attempt.attemptNo}, #{attempt.modelConfigId},
                #{attempt.vendorAccountId}, #{attempt.status}, #{attempt.claimToken},
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "attempt.id")
    int insertAttempt(@Param("attempt") TaskModelRouteAttempt attempt);

    @Select("SELECT * FROM task_model_route_attempts WHERE id = #{id} FOR UPDATE")
    TaskModelRouteAttempt findByIdForUpdate(@Param("id") Long id);

    @Select("SELECT COUNT(1) FROM task_model_route_attempts WHERE task_id = #{taskId}")
    int countByTaskId(@Param("taskId") Long taskId);

    @Select("""
            SELECT vendor_account_id
            FROM task_model_route_attempts
            WHERE task_id = #{taskId}
            ORDER BY attempt_no ASC
            """)
    List<Long> findAttemptedVendorAccountIds(@Param("taskId") Long taskId);

    @Update("""
            UPDATE task_model_route_attempts
            SET status = #{status},
                delivery_state = #{request.deliveryState},
                failure_stage = #{request.failureStage},
                error_code = #{request.errorCode},
                error_message = #{developerMessage},
                user_message = #{userMessage},
                developer_message = #{developerMessage},
                failure_trace_id = #{failureTraceId},
                provider_error_code = #{request.providerErrorCode},
                provider_request_id = #{request.providerRequestId},
                provider_charged = #{request.providerCharged},
                retry_after_seconds = #{request.retryAfterSeconds},
                claim_token = COALESCE(#{request.claimToken}, claim_token),
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'ACTIVE'
            """)
    int closeWithFailureContract(@Param("id") Long id,
                                 @Param("status") String status,
                                 @Param("request") RouteFailoverRequest request,
                                 @Param("userMessage") String userMessage,
                                 @Param("developerMessage") String developerMessage,
                                 @Param("failureTraceId") String failureTraceId);

    @Update("""
            UPDATE task_model_route_attempts
            SET status = #{status},
                error_code = NULL,
                error_message = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'ACTIVE'
            """)
    int close(@Param("id") Long id, @Param("status") String status);

    @Update("""
            UPDATE task_model_route_attempts
            SET status = #{status},
                error_code = NULL,
                error_message = NULL,
                user_message = NULL,
                developer_message = NULL,
                failure_trace_id = NULL,
                delivery_state = CASE
                    WHEN COALESCE(#{providerCalled}, 0) = 1 OR #{providerRequestId} IS NOT NULL THEN 'ACCEPTED'
                    ELSE delivery_state
                END,
                provider_request_id = COALESCE(#{providerRequestId}, provider_request_id),
                provider_charged = COALESCE(#{providerCalled}, provider_charged),
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'ACTIVE'
            """)
    int closeSuccess(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("providerRequestId") String providerRequestId,
                     @Param("providerCalled") Boolean providerCalled);

    @Update("""
            UPDATE task_model_route_attempts
            SET delivery_state = 'ACCEPTED',
                provider_request_id = COALESCE(#{providerRequestId}, provider_request_id),
                claim_token = COALESCE(#{claimToken}, claim_token),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'ACTIVE'
              AND (#{providerRequestId} IS NULL
                   OR provider_request_id IS NULL
                   OR provider_request_id = #{providerRequestId})
            """)
    int markProviderAccepted(@Param("id") Long id,
                             @Param("claimToken") String claimToken,
                             @Param("providerRequestId") String providerRequestId);

    @Update("""
            UPDATE task_model_route_attempts
            SET status = (
                    SELECT task.status
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ),
                error_code = (
                    SELECT CASE WHEN task.status = 'SUCCESS' THEN NULL ELSE task.error_code END
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ),
                error_message = (
                    SELECT CASE
                        WHEN task.status = 'SUCCESS' THEN NULL
                        ELSE COALESCE(task.developer_message, CONCAT('Task route attempt closed with status=', task.status))
                    END
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ),
                user_message = (
                    SELECT CASE
                        WHEN task.status = 'SUCCESS' THEN NULL
                        WHEN task.status = 'CANCELLED' THEN COALESCE(task.user_message, '任务已取消')
                        ELSE COALESCE(task.user_message, '任务执行失败，请稍后重试')
                    END
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ),
                developer_message = (
                    SELECT CASE
                        WHEN task.status = 'SUCCESS' THEN NULL
                        ELSE COALESCE(task.developer_message, CONCAT('Task route attempt closed with status=', task.status))
                    END
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ),
                failure_trace_id = (
                    SELECT CASE WHEN task.status = 'SUCCESS' THEN NULL ELSE task.failure_trace_id END
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ),
                provider_error_code = (
                    SELECT CASE WHEN task.status = 'SUCCESS' THEN NULL ELSE task.provider_error_code END
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ),
                provider_request_id = COALESCE((
                    SELECT task.provider_request_id
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ), provider_request_id),
                finished_at = COALESCE((
                    SELECT task.finished_at
                    FROM ai_tasks task
                    WHERE task.id = task_model_route_attempts.task_id
                ), CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE status = 'ACTIVE'
              AND EXISTS (
                SELECT 1
                FROM ai_tasks task
                WHERE task.id = task_model_route_attempts.task_id
                  AND task.status IN ('SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED')
              )
            """)
    int closeAttemptsForTerminalTasks();
}
