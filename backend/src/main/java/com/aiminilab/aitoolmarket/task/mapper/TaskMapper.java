package com.aiminilab.aitoolmarket.task.mapper;

import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.task.dto.TaskResultResponse;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class TaskMapper {

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
        task.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        task.setFinishedAt(finishedAt == null ? null : finishedAt.toLocalDateTime());
        return task;
    };

    public TaskMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(AiTask task) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO ai_tasks
                      (task_no, user_id, tool_id, status, progress, progress_message,
                       params_json, idempotency_key, estimated_credit_cost, queued_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, task.getTaskNo());
            ps.setLong(2, task.getUserId());
            ps.setLong(3, task.getToolId());
            ps.setString(4, TaskStatus.QUEUED.name());
            ps.setInt(5, 0);
            ps.setString(6, "任务已排队");
            ps.setString(7, task.getParamsJson());
            ps.setString(8, task.getIdempotencyKey());
            ps.setInt(9, task.getEstimatedCreditCost());
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    public Optional<AiTask> findByIdAndUserId(Long taskId, Long userId) {
        List<AiTask> tasks = jdbcTemplate.query(baseSql() + """
                WHERE t.id = ? AND t.user_id = ?
                """, taskRowMapper, taskId, userId);
        return tasks.stream().findFirst();
    }

    public Optional<AiTask> findById(Long taskId) {
        List<AiTask> tasks = jdbcTemplate.query(baseSql() + """
                WHERE t.id = ?
                """, taskRowMapper, taskId);
        return tasks.stream().findFirst();
    }

    public List<AiTask> findByUserId(Long userId) {
        return jdbcTemplate.query(baseSql() + """
                WHERE t.user_id = ?
                ORDER BY t.id DESC
                """, taskRowMapper, userId);
    }

    public List<AiTask> findForAdmin(String status, String toolCode, Long userId) {
        StringBuilder sql = new StringBuilder(baseSql()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            params.add(status);
        }
        if (toolCode != null && !toolCode.isBlank()) {
            sql.append(" AND tool.tool_code = ?");
            params.add(toolCode);
        }
        if (userId != null) {
            sql.append(" AND t.user_id = ?");
            params.add(userId);
        }
        sql.append(" ORDER BY t.id DESC");
        return jdbcTemplate.query(sql.toString(), taskRowMapper, params.toArray());
    }

    public Optional<TaskResultResponse> findFirstResult(Long taskId) {
        List<TaskResultResponse> results = jdbcTemplate.query("""
                SELECT resource_type, content_text
                FROM ai_result_resources
                WHERE task_id = ?
                ORDER BY sort_order ASC, id ASC
                LIMIT 1
                """, (rs, rowNum) -> new TaskResultResponse(
                rs.getString("resource_type"),
                rs.getString("content_text")
        ), taskId);
        return results.stream().findFirst();
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

    public void markSuccess(Long taskId) {
        jdbcTemplate.update("""
                UPDATE ai_tasks
                SET status = 'SUCCESS', progress = 100, progress_message = ?,
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, "生成完成", taskId);
    }

    public void markFailed(Long taskId, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE ai_tasks
                SET status = 'FAILED', progress = 100, progress_message = ?,
                    error_code = ?, error_message = ?,
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, errorMessage, errorCode, errorMessage, taskId);
    }

    public void resetToQueued(Long taskId) {
        jdbcTemplate.update("""
                UPDATE ai_tasks
                SET status = 'QUEUED', progress = 0, progress_message = ?,
                    error_code = NULL, error_message = NULL,
                    queued_at = CURRENT_TIMESTAMP, started_at = NULL,
                    finished_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, "任务已重新排队", taskId);
    }

    public void cancel(Long taskId) {
        jdbcTemplate.update("""
                UPDATE ai_tasks
                SET status = 'CANCELLED', progress = 100, progress_message = ?,
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, "管理员已取消任务", taskId);
    }

    public void insertResult(Long taskId, Long userId, String resourceType, String contentText) {
        jdbcTemplate.update("""
                INSERT INTO ai_result_resources
                  (task_id, user_id, resource_type, content_text, sort_order)
                VALUES (?, ?, ?, ?, 0)
                """, taskId, userId, resourceType, contentText);
    }

    private String baseSql() {
        return """
                SELECT t.*, tool.tool_code, tool.tool_name
                FROM ai_tasks t
                JOIN ai_tools tool ON tool.id = t.tool_id
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
    }
}
