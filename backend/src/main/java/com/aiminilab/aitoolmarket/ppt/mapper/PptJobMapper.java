package com.aiminilab.aitoolmarket.ppt.mapper;

import com.aiminilab.aitoolmarket.ppt.entity.PptJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface PptJobMapper extends BaseMapper<PptJob> {

    @Insert("""
            INSERT INTO ppt_jobs (
              user_id, project_id, root_job_id, job_type, status, engine_code,
              external_job_id, idempotency_key, attempt_no, progress, progress_message,
              request_json, result_json, error_code, error_message, retryable,
              reserved_credits, actual_credits, credit_state, next_poll_at,
              submission_started_at, reconcile_started_at, last_engine_heartbeat_at, deadline_at,
              started_at, finished_at, created_at, updated_at
            ) VALUES (
              #{job.userId}, #{job.projectId}, #{job.rootJobId}, #{job.jobType}, #{job.status},
              #{job.engineCode}, #{job.externalJobId}, #{job.idempotencyKey}, #{job.attemptNo},
              #{job.progress}, #{job.progressMessage}, #{job.requestJson}, #{job.resultJson},
              #{job.errorCode}, #{job.errorMessage}, #{job.retryable}, #{job.reservedCredits},
              #{job.actualCredits}, #{job.creditState}, #{job.nextPollAt},
              #{job.submissionStartedAt}, #{job.reconcileStartedAt}, #{job.lastEngineHeartbeatAt},
              #{job.deadlineAt}, #{job.startedAt},
              #{job.finishedAt}, #{job.createdAt}, #{job.updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "job.id")
    int insertJob(@Param("job") PptJob job);

    @Select("""
            SELECT *
            FROM ppt_jobs
            WHERE id = #{id} AND user_id = #{userId}
            LIMIT 1
            """)
    PptJob findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM ppt_jobs
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    PptJob findByIdempotency(@Param("userId") Long userId,
                             @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT *
            FROM ppt_jobs
            WHERE project_id = #{projectId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<PptJob> findRecentByProject(@Param("projectId") Long projectId,
                                     @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM ppt_jobs
            WHERE project_id = #{projectId}
              AND status IN ('CREATED', 'CREDIT_RESERVED', 'SUBMITTED', 'QUEUED', 'RUNNING', 'RECONCILING')
            """)
    long countActiveByProject(@Param("projectId") Long projectId);

    @Select("""
            SELECT *
            FROM ppt_jobs
            WHERE status IN ('CREATED', 'CREDIT_RESERVED', 'SUBMITTED', 'RUNNING', 'RECONCILING')
              AND (next_poll_at IS NULL OR next_poll_at <= #{now})
              AND (lease_expires_at IS NULL OR lease_expires_at < #{now})
            ORDER BY COALESCE(next_poll_at, created_at), id
            LIMIT #{limit}
            """)
    List<PptJob> findRecoverable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM ppt_jobs
            WHERE credit_state IN ('SETTLEMENT_PENDING', 'RELEASE_PENDING')
            ORDER BY updated_at, id
            LIMIT #{limit}
            """)
    List<PptJob> findPendingCreditOperations(@Param("limit") int limit);

    @Update("""
            UPDATE ppt_jobs
            SET lease_owner = #{owner}, lease_expires_at = #{expiresAt}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status IN ('CREATED', 'CREDIT_RESERVED', 'SUBMITTED', 'RUNNING', 'RECONCILING')
              AND (lease_expires_at IS NULL OR lease_expires_at < #{now} OR lease_owner = #{owner})
            """)
    int claimLease(@Param("id") Long id,
                   @Param("owner") String owner,
                   @Param("now") LocalDateTime now,
                   @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE ppt_jobs
            SET lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND lease_owner = #{owner}
            """)
    int releaseLease(@Param("id") Long id, @Param("owner") String owner);

    @Update("""
            UPDATE ppt_jobs
            SET status = #{nextStatus},
                reserved_credits = #{reservedCredits},
                credit_state = #{creditState},
                progress_message = #{message},
                submission_started_at = COALESCE(submission_started_at, CURRENT_TIMESTAMP),
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int markReserved(@Param("id") Long id,
                     @Param("expectedStatus") String expectedStatus,
                     @Param("nextStatus") String nextStatus,
                     @Param("reservedCredits") int reservedCredits,
                     @Param("creditState") String creditState,
                     @Param("message") String message);

    @Update("""
            UPDATE ppt_jobs
            SET status = #{status},
                external_job_id = #{externalJobId},
                progress = #{progress},
                progress_message = #{message},
                result_json = #{resultJson},
                next_poll_at = #{nextPollAt},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status IN ('CREATED', 'CREDIT_RESERVED', 'SUBMITTED', 'RUNNING', 'RECONCILING')
            """)
    int updateActiveState(@Param("id") Long id,
                          @Param("status") String status,
                          @Param("externalJobId") String externalJobId,
                          @Param("progress") int progress,
                          @Param("message") String message,
                          @Param("resultJson") String resultJson,
                          @Param("nextPollAt") LocalDateTime nextPollAt);

    @Update("""
            UPDATE ppt_jobs
            SET reconcile_started_at = COALESCE(reconcile_started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status IN ('CREATED', 'CREDIT_RESERVED', 'SUBMITTED', 'RUNNING', 'RECONCILING')
            """)
    int markReconciliationStarted(@Param("id") Long id);

    @Update("""
            UPDATE ppt_jobs
            SET last_engine_heartbeat_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status IN ('SUBMITTED', 'RUNNING', 'RECONCILING')
            """)
    int touchEngineHeartbeat(@Param("id") Long id);

    @Update("""
            UPDATE ppt_jobs
            SET status = #{status},
                progress = #{progress},
                progress_message = #{message},
                result_json = #{resultJson},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                retryable = #{retryable},
                actual_credits = #{actualCredits},
                credit_state = #{creditState},
                next_poll_at = NULL,
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status IN ('CREATED', 'CREDIT_RESERVED', 'SUBMITTED', 'RUNNING', 'RECONCILING')
            """)
    int finish(@Param("id") Long id,
               @Param("status") String status,
               @Param("progress") int progress,
               @Param("message") String message,
               @Param("resultJson") String resultJson,
               @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage,
               @Param("retryable") boolean retryable,
               @Param("actualCredits") int actualCredits,
               @Param("creditState") String creditState);

    @Update("""
            UPDATE ppt_jobs
            SET credit_state = #{nextState},
                actual_credits = #{actualCredits},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND credit_state = #{expectedState}
            """)
    int updateCreditState(@Param("id") Long id,
                          @Param("expectedState") String expectedState,
                          @Param("nextState") String nextState,
                          @Param("actualCredits") int actualCredits);
}
