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
            <if test="status != null and status.trim() != ''">
              AND t.status = #{status}
            </if>
            <if test="toolCode != null and toolCode.trim() != ''">
              AND tool.tool_code = #{toolCode}
            </if>
            ORDER BY t.id DESC
            LIMIT #{limit} OFFSET #{offset}
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
              AND COALESCE(t.user_deleted, 0) = 0
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
            UPDATE ai_tasks
            SET params_json = #{paramsJson}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
            """)
    int updateParamsJson(@Param("taskId") Long taskId, @Param("paramsJson") String paramsJson);

    @Update("""
            <script>
            UPDATE ai_tasks
            SET status = 'SUCCESS', progress = 100, progress_message = '生成完成',
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
            SET status = #{status}, progress = 100, progress_message = #{progressMessage},
                error_code = #{errorCode}, error_message = #{errorMessage},
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
            SET status = 'QUEUED', progress = 0, progress_message = '任务已重新排队',
                error_code = NULL, error_message = NULL,
                retry_count = retry_count + 1,
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
}
