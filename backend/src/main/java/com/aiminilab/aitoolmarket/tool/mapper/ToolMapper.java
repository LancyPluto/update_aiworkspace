package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.common.enums.ToolStatus;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ToolMapper {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<ToolCategory> categoryRowMapper = (rs, rowNum) -> {
        ToolCategory category = new ToolCategory();
        category.setId(rs.getLong("id"));
        category.setCategoryCode(rs.getString("category_code"));
        category.setCategoryName(rs.getString("category_name"));
        category.setSortOrder(rs.getInt("sort_order"));
        category.setStatus(rs.getString("status"));
        return category;
    };

    private final RowMapper<AiTool> toolRowMapper = (rs, rowNum) -> {
        AiTool tool = new AiTool();
        tool.setId(rs.getLong("id"));
        tool.setToolCode(rs.getString("tool_code"));
        tool.setToolName(rs.getString("tool_name"));
        tool.setCategoryId(rs.getLong("category_id"));
        tool.setCategoryName(rs.getString("category_name"));
        tool.setDescription(rs.getString("description"));
        tool.setCoverUrl(rs.getString("cover_url"));
        tool.setStatus(rs.getString("status"));
        tool.setEstimatedCreditCost(rs.getInt("estimated_credit_cost"));
        return tool;
    };

    private final RowMapper<ToolFieldItem> fieldRowMapper = (rs, rowNum) -> {
        ToolFieldItem item = new ToolFieldItem();
        item.setFieldKey(rs.getString("field_key"));
        item.setFieldName(rs.getString("field_name"));
        item.setFieldType(rs.getString("field_type"));
        item.setPlaceholder(rs.getString("placeholder"));
        item.setOptionsJson(rs.getString("options_json"));
        item.setRequired(rs.getInt("required") == 1);
        item.setSortOrder(rs.getInt("sort_order"));
        return item;
    };

    public ToolMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureDefaultCategory() {
        jdbcTemplate.update("""
                INSERT INTO tool_categories (category_code, category_name, sort_order, status)
                VALUES ('copywriting', 'Copywriting', 1, 'ACTIVE')
                ON DUPLICATE KEY UPDATE category_name = VALUES(category_name), status = VALUES(status)
                """);
    }

    public List<ToolCategory> findActiveCategories() {
        return jdbcTemplate.query("""
                SELECT * FROM tool_categories
                WHERE status = 'ACTIVE'
                ORDER BY sort_order ASC, id ASC
                """, categoryRowMapper);
    }

    public List<AiTool> findTools(boolean onlineOnly, String keyword, Long categoryId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.*, c.category_name
                FROM ai_tools t
                JOIN tool_categories c ON c.id = t.category_id
                WHERE t.is_deleted = 0
                """);
        List<Object> params = appendToolFilters(sql, onlineOnly, keyword, categoryId, status);
        sql.append(" ORDER BY t.id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(sql.toString(), toolRowMapper, params.toArray());
    }

    public long countTools(boolean onlineOnly, String keyword, Long categoryId, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM ai_tools t
                JOIN tool_categories c ON c.id = t.category_id
                WHERE t.is_deleted = 0
                """);
        List<Object> params = appendToolFilters(sql, onlineOnly, keyword, categoryId, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    public Optional<AiTool> findById(Long toolId) {
        List<AiTool> tools = jdbcTemplate.query("""
                SELECT t.*, c.category_name
                FROM ai_tools t
                JOIN tool_categories c ON c.id = t.category_id
                WHERE t.id = ? AND t.is_deleted = 0
                """, toolRowMapper, toolId);
        return tools.stream().findFirst();
    }

    public Optional<AiTool> findOnlineByCode(String toolCode) {
        List<AiTool> tools = jdbcTemplate.query("""
                SELECT t.*, c.category_name
                FROM ai_tools t
                JOIN tool_categories c ON c.id = t.category_id
                WHERE t.tool_code = ? AND t.status = 'ONLINE' AND t.is_deleted = 0
                """, toolRowMapper, toolCode);
        return tools.stream().findFirst();
    }

    public boolean existsByCode(String toolCode) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ai_tools
                WHERE tool_code = ? AND is_deleted = 0
                """, Long.class, toolCode);
        return count != null && count > 0;
    }

    public Long insertTool(AiTool tool, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO ai_tools
                      (tool_code, tool_name, category_id, description, cover_url, status, estimated_credit_cost, created_by, updated_by, is_deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, tool.getToolCode());
            ps.setString(2, tool.getToolName());
            ps.setLong(3, tool.getCategoryId());
            ps.setString(4, tool.getDescription());
            ps.setString(5, tool.getCoverUrl());
            ps.setString(6, ToolStatus.DRAFT.name());
            ps.setInt(7, tool.getEstimatedCreditCost());
            ps.setObject(8, operatorId);
            ps.setObject(9, operatorId);
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    public void updateTool(Long toolId, AiTool tool, Long operatorId) {
        jdbcTemplate.update("""
                UPDATE ai_tools
                SET tool_name = ?, category_id = ?, description = ?, cover_url = ?,
                    estimated_credit_cost = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND is_deleted = 0
                """,
                tool.getToolName(),
                tool.getCategoryId(),
                tool.getDescription(),
                tool.getCoverUrl(),
                tool.getEstimatedCreditCost(),
                operatorId,
                toolId);
    }

    public void updateToolStatus(Long toolId, ToolStatus status, Long operatorId) {
        jdbcTemplate.update("""
                UPDATE ai_tools
                SET status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND is_deleted = 0
                """, status.name(), operatorId, toolId);
    }

    public Long createActiveDefaultSchema(Long toolId, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tool_field_schemas (tool_id, schema_version, status, created_by)
                    VALUES (?, 'v1.0.0', 'ACTIVE', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, toolId);
            ps.setObject(2, operatorId);
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    public void createDefaultFields(Long schemaId) {
        insertField(schemaId, "productName", "产品名称", "text", "请输入产品名称", null, 1);
        insertField(schemaId, "targetCustomer", "目标用户", "textarea", "请输入目标用户", null, 2);
        insertField(schemaId, "style", "文案风格", "select", "请选择文案风格",
                "[{\"label\":\"种草\",\"value\":\"种草\"},{\"label\":\"专业\",\"value\":\"专业\"}]", 3);
    }

    public List<ToolFieldItem> findActiveFields(Long toolId) {
        return jdbcTemplate.query("""
                SELECT i.*
                FROM tool_field_schemas s
                JOIN tool_field_schema_items i ON i.schema_id = s.id
                WHERE s.tool_id = ? AND s.status = 'ACTIVE' AND i.status = 'ACTIVE'
                ORDER BY i.sort_order ASC, i.id ASC
                """, fieldRowMapper, toolId);
    }

    public Optional<Long> findActiveSchemaId(Long toolId) {
        List<Long> schemaIds = jdbcTemplate.query("""
                SELECT id
                FROM tool_field_schemas
                WHERE tool_id = ? AND status = 'ACTIVE'
                ORDER BY id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"), toolId);
        return schemaIds.stream().findFirst();
    }

    public void replaceActiveFields(Long schemaId, List<ToolFieldItem> fields) {
        jdbcTemplate.update("""
                UPDATE tool_field_schema_items
                SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE schema_id = ?
                """, schemaId);
        for (ToolFieldItem field : fields) {
            insertField(
                    schemaId,
                    field.getFieldKey(),
                    field.getFieldName(),
                    field.getFieldType(),
                    field.getPlaceholder(),
                    field.getOptionsJson(),
                    field.getRequired() != null && field.getRequired(),
                    field.getSortOrder()
            );
        }
    }

    private void insertField(Long schemaId, String fieldKey, String fieldName, String fieldType,
                             String placeholder, String optionsJson, int sortOrder) {
        insertField(schemaId, fieldKey, fieldName, fieldType, placeholder, optionsJson, true, sortOrder);
    }

    private void insertField(Long schemaId, String fieldKey, String fieldName, String fieldType,
                             String placeholder, String optionsJson, boolean required, int sortOrder) {
        jdbcTemplate.update("""
                INSERT INTO tool_field_schema_items
                  (schema_id, field_key, field_name, field_type, placeholder, options_json, required, sort_order, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, schemaId, fieldKey, fieldName, fieldType, placeholder, optionsJson, required ? 1 : 0, sortOrder);
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

    private List<Object> appendToolFilters(StringBuilder sql, boolean onlineOnly, String keyword, Long categoryId, String status) {
        List<Object> params = new ArrayList<>();
        if (onlineOnly) {
            sql.append(" AND t.status = 'ONLINE'");
        } else if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            params.add(status.trim());
        }
        if (categoryId != null) {
            sql.append(" AND t.category_id = ?");
            params.add(categoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(t.tool_code) LIKE ? OR LOWER(t.tool_name) LIKE ? OR LOWER(t.description) LIKE ?)");
            String likeKeyword = "%" + keyword.trim().toLowerCase() + "%";
            params.add(likeKeyword);
            params.add(likeKeyword);
            params.add(likeKeyword);
        }
        return params;
    }
}
