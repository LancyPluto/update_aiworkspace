package com.aiminilab.aitoolmarket.task.mapper;

import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.task.dto.TaskResultResponse;
import com.aiminilab.aitoolmarket.task.entity.AiResultResource;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskMapper extends BaseMapper<AiTask> {

    default Long insertTask(AiTask task) {
        task.setStatus(TaskStatus.QUEUED.name());
        task.setProgress(0);
        task.setProgressMessage("任务已排队");
        task.setQueuedAt(LocalDateTime.now());
        insert(task);
        return task.getId();
    }

    @Select("""
            SELECT t.*, tool.tool_code, tool.tool_name, tool.tool_type, tool.execution_handler, tool.input_modality, tool.output_modality,
                   COALESCE(model_config.display_name, model_config.model_name) AS model_config_name,
                   model_config.model_name AS model_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            LEFT JOIN agent_model_configs model_config ON model_config.id = t.model_config_id
              AND COALESCE(model_config.is_deleted, 0) = 0
            WHERE t.id = #{taskId} AND t.user_id = #{userId}
              AND COALESCE(t.user_deleted, 0) = 0
            """)
    AiTask selectByIdAndUserId(@Param("taskId") Long taskId, @Param("userId") Long userId);

    default Optional<AiTask> findByIdAndUserId(Long taskId, Long userId) {
        return Optional.ofNullable(selectByIdAndUserId(taskId, userId));
    }

    @Select("""
            SELECT COUNT(1)
            FROM ai_tasks
            WHERE id = #{taskId}
              AND user_id = #{userId}
              AND COALESCE(user_deleted, 0) = 0
            """)
    long countOwnedTask(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
            FROM ai_tasks
            WHERE id = #{taskId}
              AND user_id = #{userId}
            """)
    long countOwnedTaskIgnoringUserDeleted(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
            FROM ai_result_resources
            WHERE user_id = #{userId}
              AND content_text LIKE CONCAT('%', #{relativeKey})
            """)
    long countUserResultContainingRelativeKey(@Param("userId") Long userId,
                                              @Param("relativeKey") String relativeKey);

    @Select("""
            SELECT t.*, tool.tool_code, tool.tool_name, tool.tool_type, tool.execution_handler, tool.input_modality, tool.output_modality,
                   COALESCE(model_config.display_name, model_config.model_name) AS model_config_name,
                   model_config.model_name AS model_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            LEFT JOIN agent_model_configs model_config ON model_config.id = t.model_config_id
              AND COALESCE(model_config.is_deleted, 0) = 0
            WHERE t.id = #{taskId}
            """)
    AiTask selectDetailById(@Param("taskId") Long taskId);

    default Optional<AiTask> findById(Long taskId) {
        return Optional.ofNullable(selectDetailById(taskId));
    }

    @Select("SELECT * FROM ai_tasks WHERE id = #{taskId} FOR UPDATE")
    AiTask selectByIdForUpdate(@Param("taskId") Long taskId);

    @Select("""
            <script>
            SELECT t.*, tool.tool_code, tool.tool_name, tool.tool_type, tool.execution_handler, tool.input_modality, tool.output_modality,
                   COALESCE(model_config.display_name, model_config.model_name) AS model_config_name,
                   model_config.model_name AS model_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            LEFT JOIN agent_model_configs model_config ON model_config.id = t.model_config_id
              AND COALESCE(model_config.is_deleted, 0) = 0
            WHERE t.user_id = #{userId}
              AND COALESCE(t.user_deleted, 0) = 0
            <if test="idempotencyKey != null and idempotencyKey.trim() != ''">
              AND t.idempotency_key = #{idempotencyKey}
            </if>
            ORDER BY t.id DESC
            LIMIT 1
            </script>
            """)
    AiTask selectByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                           @Param("idempotencyKey") String idempotencyKey);

    default Optional<AiTask> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectByUserIdAndIdempotencyKey(userId, idempotencyKey));
    }

    @Select("""
            SELECT t.*, tool.tool_code, tool.tool_name, tool.tool_type, tool.execution_handler,
                   tool.input_modality, tool.output_modality
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            WHERE t.user_id = #{userId}
              AND t.idempotency_key = #{idempotencyKey}
            ORDER BY t.id DESC
            LIMIT 1
            """)
    AiTask selectByUserIdAndIdempotencyKeyIncludingDeleted(@Param("userId") Long userId,
                                                            @Param("idempotencyKey") String idempotencyKey);

    default Optional<AiTask> findByUserIdAndIdempotencyKeyIncludingDeleted(Long userId,
                                                                           String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectByUserIdAndIdempotencyKeyIncludingDeleted(userId, idempotencyKey));
    }

    @Select("""
            SELECT * FROM ai_tasks
            WHERE user_id = #{userId}
              AND idempotency_key = #{idempotencyKey}
            ORDER BY id DESC
            LIMIT 1
            """)
    AiTask selectRawByUserIdAndIdempotencyKeyIncludingDeleted(@Param("userId") Long userId,
                                                               @Param("idempotencyKey") String idempotencyKey);

    default Optional<AiTask> findRawByUserIdAndIdempotencyKeyIncludingDeleted(Long userId,
                                                                              String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectRawByUserIdAndIdempotencyKeyIncludingDeleted(
                userId,
                idempotencyKey
        ));
    }

    @Select("""
            <script>
            SELECT t.*, tool.tool_code, tool.tool_name, tool.tool_type, tool.execution_handler, tool.input_modality, tool.output_modality,
                   COALESCE(model_config.display_name, model_config.model_name) AS model_config_name,
                   model_config.model_name AS model_name
            FROM ai_tasks t
            INNER JOIN (
                SELECT t2.id FROM ai_tasks t2
                JOIN ai_tools tool2 ON tool2.id = t2.tool_id
                WHERE t2.user_id = #{userId}
                  AND (t2.user_deleted IS NULL OR t2.user_deleted = 0)
                  AND NOT EXISTS (
                    SELECT 1 FROM workflow_step_attempts workflow_attempt
                    WHERE workflow_attempt.child_task_id = t2.id
                  )
                <if test="status != null and status.trim() != ''">
                  AND t2.status = #{status}
                </if>
                <if test="toolCode != null and toolCode.trim() != ''">
                  AND tool2.tool_code = #{toolCode}
                </if>
                ORDER BY t2.id DESC
                LIMIT #{limit} OFFSET #{offset}
            ) page ON t.id = page.id
            JOIN ai_tools tool ON tool.id = t.tool_id
            LEFT JOIN agent_model_configs model_config ON model_config.id = t.model_config_id
              AND (model_config.is_deleted IS NULL OR model_config.is_deleted = 0)
            ORDER BY t.id DESC
            </script>
            """)
    List<AiTask> findByUserId(@Param("userId") Long userId,
                              @Param("status") String status,
                              @Param("toolCode") String toolCode,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            WHERE t.user_id = #{userId}
              AND (t.user_deleted IS NULL OR t.user_deleted = 0)
              AND NOT EXISTS (
                SELECT 1 FROM workflow_step_attempts workflow_attempt
                WHERE workflow_attempt.child_task_id = t.id
              )
            <if test="status != null and status.trim() != ''">
              AND t.status = #{status}
            </if>
            <if test="toolCode != null and toolCode.trim() != ''">
              AND tool.tool_code = #{toolCode}
            </if>
            </script>
            """)
    long countByUserId(@Param("userId") Long userId,
                       @Param("status") String status,
                       @Param("toolCode") String toolCode);

    @Select("""
            <script>
            SELECT t.*, tool.tool_code, tool.tool_name, tool.tool_type, tool.execution_handler, tool.input_modality, tool.output_modality,
                   COALESCE(model_config.display_name, model_config.model_name) AS model_config_name,
                   model_config.model_name AS model_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            LEFT JOIN agent_model_configs model_config ON model_config.id = t.model_config_id
              AND COALESCE(model_config.is_deleted, 0) = 0
            WHERE 1 = 1
            <if test="status != null and status.trim() != ''">
              AND t.status = #{status}
            </if>
            <if test="toolCode != null and toolCode.trim() != ''">
              AND tool.tool_code = #{toolCode}
            </if>
            <if test="userId != null">
              AND t.user_id = #{userId}
            </if>
            <if test="taskId != null">
              AND t.id = #{taskId}
            </if>
            ORDER BY t.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AiTask> findForAdmin(@Param("status") String status,
                              @Param("toolCode") String toolCode,
                              @Param("userId") Long userId,
                              @Param("taskId") Long taskId,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    @Select("""
            SELECT t.*, tool.tool_code, tool.tool_name, tool.tool_type, tool.execution_handler, tool.input_modality, tool.output_modality,
                   COALESCE(model_config.display_name, model_config.model_name) AS model_config_name,
                   model_config.model_name AS model_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            LEFT JOIN agent_model_configs model_config ON model_config.id = t.model_config_id
              AND COALESCE(model_config.is_deleted, 0) = 0
            WHERE t.status IN ('QUEUED', 'PROCESSING')
              AND COALESCE(t.updated_at, t.started_at, t.queued_at, t.created_at) < #{cutoff}
              AND NOT EXISTS (
                SELECT 1 FROM workflow_runs workflow_run
                WHERE workflow_run.root_task_id = t.id
              )
              AND NOT EXISTS (
                SELECT 1 FROM workflow_step_attempts workflow_attempt
                WHERE workflow_attempt.child_task_id = t.id
              )
            ORDER BY t.id ASC
            LIMIT #{limit}
            """)
    List<AiTask> findStaleActiveTasks(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            WHERE 1 = 1
            <if test="status != null and status.trim() != ''">
              AND t.status = #{status}
            </if>
            <if test="toolCode != null and toolCode.trim() != ''">
              AND tool.tool_code = #{toolCode}
            </if>
            <if test="userId != null">
              AND t.user_id = #{userId}
            </if>
            <if test="taskId != null">
              AND t.id = #{taskId}
            </if>
            </script>
            """)
    long countForAdmin(@Param("status") String status,
                       @Param("toolCode") String toolCode,
                       @Param("userId") Long userId,
                       @Param("taskId") Long taskId);

    @Select("""
            SELECT resource_type, content_text
            FROM ai_result_resources
            WHERE task_id = #{taskId}
            ORDER BY sort_order ASC, id ASC
            LIMIT 1
            """)
    @ConstructorArgs({
            @Arg(column = "resource_type", javaType = String.class),
            @Arg(column = "content_text", javaType = String.class)
    })
    TaskResultResponse selectFirstResult(@Param("taskId") Long taskId);

    default Optional<TaskResultResponse> findFirstResult(Long taskId) {
        return Optional.ofNullable(selectFirstResult(taskId));
    }

    @Select("""
            SELECT id, task_id, user_id, resource_type, content_text, sort_order
            FROM ai_result_resources
            WHERE task_id = #{taskId}
            ORDER BY sort_order ASC, id ASC
            """)
    List<AiResultResource> findResultResources(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ai_result_resources
            SET content_text = #{contentText}
            WHERE id = #{id}
            """)
    int updateResultContent(@Param("id") Long id, @Param("contentText") String contentText);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'PROCESSING', progress = #{progress}, progress_message = #{progressMessage},
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markProcessing(@Param("taskId") Long taskId,
                       @Param("progress") int progress,
                       @Param("progressMessage") String progressMessage,
                       @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'PROCESSING', progress = #{progress}, progress_message = #{progressMessage},
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND (claim_token IS NULL OR claim_token = #{claimToken})
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markProcessingGuarded(@Param("taskId") Long taskId,
                              @Param("claimToken") String claimToken,
                              @Param("progress") int progress,
                              @Param("progressMessage") String progressMessage,
                              @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            UPDATE ai_tasks
            SET status = 'PROCESSING',
                progress = CASE WHEN progress IS NULL OR progress < 1 THEN 1 ELSE progress END,
                progress_message = CASE
                    WHEN progress_message IS NULL OR progress_message = '' OR status <> 'PROCESSING'
                    THEN '任务已被 Worker 领取'
                    ELSE progress_message
                END,
                claimed_by = #{workerId},
                claim_token = #{claimToken},
                lease_until = #{leaseUntil},
                claimed_at = CURRENT_TIMESTAMP,
                lease_renewed_at = CURRENT_TIMESTAMP,
                execution_attempt = COALESCE(execution_attempt, 0) + 1,
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND (
                status IN ('CREATED', 'QUEUED', 'RETRYING')
                OR (status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until < CURRENT_TIMESTAMP)
              )
            """)
    int claimForExecution(@Param("taskId") Long taskId,
                          @Param("workerId") String workerId,
                          @Param("claimToken") String claimToken,
                          @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE ai_tasks
            SET lease_until = #{leaseUntil},
                lease_renewed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status = 'PROCESSING'
              AND claim_token = #{claimToken}
            """)
    int renewLease(@Param("taskId") Long taskId,
                   @Param("claimToken") String claimToken,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE ai_tasks
            SET provider_checkpoint_json = #{checkpointJson},
                provider_checkpoint_version = provider_checkpoint_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status = 'PROCESSING'
              AND claim_token = #{claimToken}
              AND lease_until IS NOT NULL
              AND lease_until >= CURRENT_TIMESTAMP
              AND provider_checkpoint_version = #{expectedVersion}
            """)
    int updateProviderCheckpointGuarded(@Param("taskId") Long taskId,
                                        @Param("claimToken") String claimToken,
                                        @Param("expectedVersion") int expectedVersion,
                                        @Param("checkpointJson") String checkpointJson);

    @Update("""
            UPDATE ai_tasks
            SET selected_model_config_id = #{selectedModelConfigId},
                selected_vendor_account_id = #{selectedVendorAccountId},
                current_route_attempt_id = #{routeAttemptId},
                model_snapshot_json = #{modelSnapshotJson},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND current_route_attempt_id IS NULL
            """)
    int assignInitialRoute(@Param("taskId") Long taskId,
                           @Param("selectedModelConfigId") Long selectedModelConfigId,
                           @Param("selectedVendorAccountId") Long selectedVendorAccountId,
                           @Param("routeAttemptId") Long routeAttemptId,
                           @Param("modelSnapshotJson") String modelSnapshotJson);

    @Update("""
            UPDATE ai_tasks
            SET selected_model_config_id = #{selectedModelConfigId},
                selected_vendor_account_id = #{selectedVendorAccountId},
                current_route_attempt_id = #{newRouteAttemptId},
                model_snapshot_json = #{modelSnapshotJson},
                provider_checkpoint_json = NULL,
                provider_checkpoint_version = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status = 'PROCESSING'
              AND claim_token = #{claimToken}
              AND current_route_attempt_id = #{expectedRouteAttemptId}
            """)
    int switchRouteGuarded(@Param("taskId") Long taskId,
                           @Param("claimToken") String claimToken,
                           @Param("expectedRouteAttemptId") Long expectedRouteAttemptId,
                           @Param("selectedModelConfigId") Long selectedModelConfigId,
                           @Param("selectedVendorAccountId") Long selectedVendorAccountId,
                           @Param("newRouteAttemptId") Long newRouteAttemptId,
                           @Param("modelSnapshotJson") String modelSnapshotJson);

    @Update("""
            UPDATE ai_tasks
            SET selected_model_config_id = #{selectedModelConfigId},
                selected_vendor_account_id = #{selectedVendorAccountId},
                current_route_attempt_id = NULL,
                model_snapshot_json = #{modelSnapshotJson},
                provider_checkpoint_json = NULL,
                provider_checkpoint_version = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status = 'QUEUED'
            """)
    int clearRouteForRetry(@Param("taskId") Long taskId,
                           @Param("selectedModelConfigId") Long selectedModelConfigId,
                           @Param("selectedVendorAccountId") Long selectedVendorAccountId,
                           @Param("modelSnapshotJson") String modelSnapshotJson);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'AWAITING_USER', progress = #{progress}, progress_message = #{progressMessage},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markAwaitingUser(@Param("taskId") Long taskId,
                         @Param("progress") int progress,
                         @Param("progressMessage") String progressMessage,
                         @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'AWAITING_FUNDS', progress_message = #{progressMessage},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markAwaitingFunds(@Param("taskId") Long taskId,
                          @Param("progressMessage") String progressMessage,
                          @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            UPDATE ai_tasks
            SET params_json = #{paramsJson}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
            """)
    int updateParamsJson(@Param("taskId") Long taskId, @Param("paramsJson") String paramsJson);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'SUCCESS', progress = 100, progress_message = '生成完成',
                claimed_by = NULL, claim_token = NULL, lease_until = NULL,
                claimed_at = NULL, lease_renewed_at = NULL,
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markSuccess(@Param("taskId") Long taskId,
                    @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'SUCCESS', progress = 100, progress_message = '生成完成',
                claimed_by = NULL, claim_token = NULL, lease_until = NULL,
                claimed_at = NULL, lease_renewed_at = NULL,
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND (claim_token IS NULL OR claim_token = #{claimToken})
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markSuccessGuarded(@Param("taskId") Long taskId,
                           @Param("claimToken") String claimToken,
                           @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = #{status}, progress = 100, progress_message = #{progressMessage},
                error_code = #{errorCode}, error_message = #{errorMessage},
                claimed_by = NULL, claim_token = NULL, lease_until = NULL,
                claimed_at = NULL, lease_renewed_at = NULL,
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markFailed(@Param("taskId") Long taskId,
                   @Param("status") String status,
                   @Param("errorCode") String errorCode,
                   @Param("progressMessage") String progressMessage,
                   @Param("errorMessage") String errorMessage,
                   @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = #{status}, progress = 100, progress_message = #{progressMessage},
                error_code = #{errorCode}, error_message = #{errorMessage},
                claimed_by = NULL, claim_token = NULL, lease_until = NULL,
                claimed_at = NULL, lease_renewed_at = NULL,
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND (claim_token IS NULL OR claim_token = #{claimToken})
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markFailedGuarded(@Param("taskId") Long taskId,
                          @Param("claimToken") String claimToken,
                          @Param("status") String status,
                          @Param("errorCode") String errorCode,
                          @Param("progressMessage") String progressMessage,
                          @Param("errorMessage") String errorMessage,
                          @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'QUEUED', progress = 0, progress_message = '任务已重新排队',
                error_code = NULL, error_message = NULL,
                retry_count = retry_count + 1,
                claimed_by = NULL, claim_token = NULL, lease_until = NULL,
                claimed_at = NULL, lease_renewed_at = NULL,
                queued_at = CURRENT_TIMESTAMP, started_at = NULL,
                finished_at = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int resetToQueued(@Param("taskId") Long taskId,
                      @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'RETRYING', progress = 0, progress_message = '任务正在重试',
                error_code = NULL, error_message = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int markRetrying(@Param("taskId") Long taskId,
                     @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'CANCELLED', progress = 100, progress_message = '管理员已取消任务',
                claimed_by = NULL, claim_token = NULL, lease_until = NULL,
                claimed_at = NULL, lease_renewed_at = NULL,
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN
              <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
            </script>
            """)
    int cancel(@Param("taskId") Long taskId,
               @Param("expectedStatuses") List<String> expectedStatuses);

    @Update("""
            UPDATE ai_tasks
            SET user_deleted = 1,
                user_deleted_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND user_id = #{userId}
              AND COALESCE(user_deleted, 0) = 0
            """)
    int softDeleteForUser(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO ai_result_resources
              (task_id, user_id, resource_type, content_text, sort_order)
            VALUES (#{resource.taskId}, #{resource.userId}, #{resource.resourceType}, #{resource.contentText}, #{resource.sortOrder})
            """)
    void insertResultResource(@Param("resource") AiResultResource resource);

    default void insertResult(Long taskId, Long userId, String resourceType, String contentText) {
        AiResultResource resource = new AiResultResource();
        resource.setTaskId(taskId);
        resource.setUserId(userId);
        resource.setResourceType(resourceType);
        resource.setContentText(contentText);
        resource.setSortOrder(0);
        insertResultResource(resource);
    }

    @Select("""
            SELECT COALESCE(SUM(charged_credits), 0)
            FROM billing_usage_logs
            WHERE source_type = 'TASK' AND source_id = #{taskId}
            """)
    int sumConsumedCreditsByTaskId(@Param("taskId") Long taskId);

    @Select("""
            <script>
            SELECT task_id, resource_type, content_text
            FROM (
                SELECT task_id, resource_type, content_text,
                       ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY sort_order ASC, id ASC) AS rn
                FROM ai_result_resources
                WHERE task_id IN
                <foreach item="id" collection="taskIds" open="(" separator="," close=")">#{id}</foreach>
            ) ranked
            WHERE rn = 1
            </script>
            """)
    List<java.util.Map<String, Object>> batchSelectFirstResults(@Param("taskIds") List<Long> taskIds);

    @Select("""
            <script>
            SELECT source_id AS task_id, COALESCE(SUM(charged_credits), 0) AS total_credits
            FROM billing_usage_logs
            WHERE source_type = 'TASK'
              AND source_id IN
            <foreach item="id" collection="taskIds" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY source_id
            </script>
            """)
    List<java.util.Map<String, Object>> batchSumConsumedCredits(@Param("taskIds") List<Long> taskIds);
}
