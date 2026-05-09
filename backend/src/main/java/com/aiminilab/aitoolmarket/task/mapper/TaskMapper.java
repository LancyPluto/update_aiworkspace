package com.aiminilab.aitoolmarket.task.mapper;

import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.task.dto.TaskLogResponse;
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

<<<<<<< HEAD
    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<AiTask> taskRowMapper = (rs, rowNum) -> {
        AiTask task = new AiTask();
        task.setId(rs.getLong("id"));
        task.setTaskNo(rs.getString("task_no"));
        task.setUserId(rs.getLong("user_id"));
        task.setToolId(rs.getLong("tool_id"));
        task.setToolCode(rs.getString("tool_code"));
        task.setToolName(rs.getString("tool_name"));
        task.setStatus(rs.getString("status"));
        task.setProgress(rs.getInt("progress"));
        task.setProgressMessage(rs.getString("progress_message"));
        task.setParamsJson(rs.getString("params_json"));
        task.setIdempotencyKey(rs.getString("idempotency_key"));
        task.setEstimatedCreditCost(rs.getInt("estimated_credit_cost"));
        task.setErrorCode(rs.getString("error_code"));
        task.setErrorMessage(rs.getString("error_message"));
        task.setUserNickname(rs.getString("user_nickname"));
        task.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        task.setFinishedAt(finishedAt == null ? null : finishedAt.toLocalDateTime());
        return task;
    };

    private final RowMapper<TaskLogResponse> logRowMapper = (rs, rowNum) -> new TaskLogResponse(
            rs.getLong("id"),
            rs.getString("event_type"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("message"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    public TaskMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
=======
    default Long insertTask(AiTask task) {
        task.setStatus(TaskStatus.QUEUED.name());
        task.setProgress(0);
        task.setProgressMessage("任务已排队");
        task.setQueuedAt(LocalDateTime.now());
        insert(task);
        return task.getId();
>>>>>>> origin/feature/backend-core
    }

    @Select("""
            SELECT t.*, tool.tool_code, tool.tool_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            WHERE t.id = #{taskId} AND t.user_id = #{userId}
            """)
    AiTask selectByIdAndUserId(@Param("taskId") Long taskId, @Param("userId") Long userId);

    default Optional<AiTask> findByIdAndUserId(Long taskId, Long userId) {
        return Optional.ofNullable(selectByIdAndUserId(taskId, userId));
    }

    @Select("""
            SELECT t.*, tool.tool_code, tool.tool_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            WHERE t.id = #{taskId}
            """)
    AiTask selectDetailById(@Param("taskId") Long taskId);

    default Optional<AiTask> findById(Long taskId) {
        return Optional.ofNullable(selectDetailById(taskId));
    }

    @Select("""
            <script>
            SELECT t.*, tool.tool_code, tool.tool_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            WHERE t.user_id = #{userId}
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
            SELECT t.*, tool.tool_code, tool.tool_name
            FROM ai_tasks t
            JOIN ai_tools tool ON tool.id = t.tool_id
            WHERE t.user_id = #{userId}
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
            SELECT t.*, tool.tool_code, tool.tool_name
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
            ORDER BY t.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AiTask> findForAdmin(@Param("status") String status,
                              @Param("toolCode") String toolCode,
                              @Param("userId") Long userId,
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
            </script>
            """)
    long countForAdmin(@Param("status") String status,
                       @Param("toolCode") String toolCode,
                       @Param("userId") Long userId);

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

<<<<<<< HEAD
    public List<TaskLogResponse> findLogs(Long taskId) {
        return jdbcTemplate.query("""
                SELECT id, event_type, from_status, to_status, message, created_at
                FROM ai_task_logs
                WHERE task_id = ?
                ORDER BY id ASC
                """, logRowMapper, taskId);
    }

    public void insertLog(Long taskId, String eventType, String fromStatus, String toStatus, String message,
                          String operatorType, Long operatorId) {
        jdbcTemplate.update("""
                INSERT INTO ai_task_logs
                  (task_id, from_status, to_status, event_type, message, operator_type, operator_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, taskId, fromStatus, toStatus, eventType, message, operatorType, operatorId);
    }

    public void markProcessing(Long taskId, int progress, String progressMessage) {
        jdbcTemplate.update("""
                UPDATE ai_tasks
                SET status = 'PROCESSING', progress = ?, progress_message = ?,
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, progress, progressMessage, taskId);
    }
=======
    @Update("""
            UPDATE ai_tasks
            SET status = 'PROCESSING', progress = #{progress}, progress_message = #{progressMessage},
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
            """)
    void markProcessing(@Param("taskId") Long taskId,
                        @Param("progress") int progress,
                        @Param("progressMessage") String progressMessage);
>>>>>>> origin/feature/backend-core

    @Update("""
            UPDATE ai_tasks
            SET status = 'SUCCESS', progress = 100, progress_message = '生成完成',
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
            """)
    void markSuccess(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ai_tasks
            SET status = 'FAILED', progress = 100, progress_message = #{errorMessage},
                error_code = #{errorCode}, error_message = #{errorMessage},
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
            """)
    void markFailed(@Param("taskId") Long taskId,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE ai_tasks
            SET status = 'QUEUED', progress = 0, progress_message = '任务已重新排队',
                error_code = NULL, error_message = NULL,
                queued_at = CURRENT_TIMESTAMP, started_at = NULL,
                finished_at = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
            """)
    void resetToQueued(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ai_tasks
            SET status = 'CANCELLED', progress = 100, progress_message = '管理员已取消任务',
                finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
            """)
    void cancel(@Param("taskId") Long taskId);

    @Insert("""
            INSERT INTO ai_result_resources
              (task_id, user_id, resource_type, content_text, sort_order)
            VALUES (#{resource.taskId}, #{resource.userId}, #{resource.resourceType}, #{resource.contentText}, #{resource.sortOrder})
            """)
    void insertResultResource(@Param("resource") AiResultResource resource);

<<<<<<< HEAD
    private String baseSql() {
        return """
                SELECT t.*, tool.tool_code, tool.tool_name, u.nickname AS user_nickname
                FROM ai_tasks t
                JOIN ai_tools tool ON tool.id = t.tool_id
                LEFT JOIN users u ON u.id = t.user_id
                """;
    }

    private Long generatedId(KeyHolder keyHolder) {
        Number key = null;
        if (!keyHolder.getKeyList().isEmpty()) {
            Object value = keyHolder.getKeyList().get(0).values().stream().findFirst().orElse(null);
            if (value instanceof Number number) {
                key = number;
            }
        }
        if (key == null) {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("Generated id is missing");
        }
        return key.longValue();
=======
    default void insertResult(Long taskId, Long userId, String resourceType, String contentText) {
        AiResultResource resource = new AiResultResource();
        resource.setTaskId(taskId);
        resource.setUserId(userId);
        resource.setResourceType(resourceType);
        resource.setContentText(contentText);
        resource.setSortOrder(0);
        insertResultResource(resource);
>>>>>>> origin/feature/backend-core
    }
}
