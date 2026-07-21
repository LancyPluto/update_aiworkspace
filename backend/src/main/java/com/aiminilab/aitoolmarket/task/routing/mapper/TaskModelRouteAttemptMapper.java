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
                error_message = #{request.errorMessage},
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
    int closeWithFailure(@Param("id") Long id,
                         @Param("status") String status,
                         @Param("request") RouteFailoverRequest request);

    @Update("""
            UPDATE task_model_route_attempts
            SET status = #{status},
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'ACTIVE'
            """)
    int close(@Param("id") Long id, @Param("status") String status);

    @Update("""
            UPDATE task_model_route_attempts
            SET status = #{status},
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
            UPDATE task_model_route_attempts attempt
            JOIN ai_tasks task ON task.id = attempt.task_id
            SET attempt.status = task.status,
                attempt.finished_at = COALESCE(task.finished_at, CURRENT_TIMESTAMP),
                attempt.updated_at = CURRENT_TIMESTAMP
            WHERE attempt.status = 'ACTIVE'
              AND task.status IN ('SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED')
            """)
    int closeAttemptsForTerminalTasks();
}
