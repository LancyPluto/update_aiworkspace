package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.common.enums.ToolStatus;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldSchema;
import com.aiminilab.aitoolmarket.tool.entity.ToolPrompt;
import com.aiminilab.aitoolmarket.tool.entity.ToolPromptVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
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
        item.setId(rs.getLong("id"));
        item.setSchemaId(rs.getLong("schema_id"));
        item.setFieldKey(rs.getString("field_key"));
        item.setFieldName(rs.getString("field_name"));
        item.setFieldType(rs.getString("field_type"));
        item.setPlaceholder(rs.getString("placeholder"));
        item.setOptionsJson(rs.getString("options_json"));
        item.setRequired(rs.getInt("required") == 1);
        item.setSortOrder(rs.getInt("sort_order"));
        return item;
    };

    private final RowMapper<ToolFieldSchema> fieldSchemaRowMapper = (rs, rowNum) -> {
        ToolFieldSchema schema = new ToolFieldSchema();
        schema.setId(rs.getLong("id"));
        schema.setToolId(rs.getLong("tool_id"));
        schema.setSchemaVersion(rs.getString("schema_version"));
        schema.setStatus(rs.getString("status"));
        schema.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        schema.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return schema;
    };

    private final RowMapper<ToolPrompt> promptRowMapper = (rs, rowNum) -> {
        ToolPrompt prompt = new ToolPrompt();
        prompt.setId(rs.getLong("id"));
        prompt.setToolId(rs.getLong("tool_id"));
        prompt.setPromptCode(rs.getString("prompt_code"));
        prompt.setPromptName(rs.getString("prompt_name"));
        long activeVersionId = rs.getLong("active_version_id");
        prompt.setActiveVersionId(rs.wasNull() ? null : activeVersionId);
        prompt.setStatus(rs.getString("status"));
        return prompt;
    };

    private final RowMapper<ToolPromptVersion> promptVersionRowMapper = (rs, rowNum) -> {
        ToolPromptVersion version = new ToolPromptVersion();
        version.setId(rs.getLong("id"));
        version.setPromptId(rs.getLong("prompt_id"));
        version.setVersionNo(rs.getString("version_no"));
        version.setSystemPrompt(rs.getString("system_prompt"));
        version.setUserPromptTemplate(rs.getString("user_prompt_template"));
        version.setOutputFormat(rs.getString("output_format"));
        version.setStatus(rs.getString("status"));
        version.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        Timestamp publishedAt = rs.getTimestamp("published_at");
        version.setPublishedAt(publishedAt == null ? null : publishedAt.toLocalDateTime());
        return version;
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

    public List<AiTool> findTools(boolean onlineOnly) {
        String statusCondition = onlineOnly ? "AND t.status = 'ONLINE'" : "";
        return jdbcTemplate.query("""
                SELECT t.*, c.category_name
                FROM ai_tools t
                JOIN tool_categories c ON c.id = t.category_id
                WHERE t.is_deleted = 0 %s
                ORDER BY t.id DESC
                """.formatted(statusCondition), toolRowMapper);
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

    public List<ToolFieldSchema> findSchemas(Long toolId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM tool_field_schemas
                WHERE tool_id = ?
                ORDER BY id DESC
                """, fieldSchemaRowMapper, toolId);
    }

    public Optional<ToolFieldSchema> findSchemaById(Long schemaId) {
        List<ToolFieldSchema> schemas = jdbcTemplate.query("""
                SELECT *
                FROM tool_field_schemas
                WHERE id = ?
                """, fieldSchemaRowMapper, schemaId);
        return schemas.stream().findFirst();
    }

    public List<ToolFieldItem> findFieldsBySchemaId(Long schemaId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM tool_field_schema_items
                WHERE schema_id = ? AND status = 'ACTIVE'
                ORDER BY sort_order ASC, id ASC
                """, fieldRowMapper, schemaId);
    }

    public Long createFieldSchema(Long toolId, String schemaVersion, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tool_field_schemas (tool_id, schema_version, status, created_by)
                    VALUES (?, ?, 'DRAFT', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, toolId);
            ps.setString(2, schemaVersion);
            ps.setObject(3, operatorId);
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    public void inactivateSchemas(Long toolId) {
        jdbcTemplate.update("""
                UPDATE tool_field_schemas
                SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE tool_id = ? AND status = 'ACTIVE'
                """, toolId);
    }

    public void activateSchema(Long schemaId) {
        jdbcTemplate.update("""
                UPDATE tool_field_schemas
                SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, schemaId);
    }

    public List<ToolPrompt> findPrompts(Long toolId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM tool_prompts
                WHERE tool_id = ?
                ORDER BY id DESC
                """, promptRowMapper, toolId);
    }

    public Optional<ToolPrompt> findPromptById(Long promptId) {
        List<ToolPrompt> prompts = jdbcTemplate.query("""
                SELECT *
                FROM tool_prompts
                WHERE id = ?
                """, promptRowMapper, promptId);
        return prompts.stream().findFirst();
    }

    public Long createPrompt(Long toolId, String promptCode, String promptName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tool_prompts (tool_id, prompt_code, prompt_name, status)
                    VALUES (?, ?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, toolId);
            ps.setString(2, promptCode);
            ps.setString(3, promptName);
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    public Long createPromptVersion(Long promptId, String versionNo, String systemPrompt, String userPromptTemplate,
                                    String outputFormat, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tool_prompt_versions
                      (prompt_id, version_no, system_prompt, user_prompt_template, output_format, status, created_by)
                    VALUES (?, ?, ?, ?, ?, 'DRAFT', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, promptId);
            ps.setString(2, versionNo);
            ps.setString(3, systemPrompt);
            ps.setString(4, userPromptTemplate);
            ps.setString(5, outputFormat);
            ps.setObject(6, operatorId);
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    public Long createActivePromptVersion(Long promptId, String versionNo, String userPromptTemplate, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tool_prompt_versions
                      (prompt_id, version_no, user_prompt_template, output_format, status, created_by, published_at)
                    VALUES (?, ?, ?, 'MARKDOWN', 'ACTIVE', ?, CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, promptId);
            ps.setString(2, versionNo);
            ps.setString(3, userPromptTemplate);
            ps.setObject(4, operatorId);
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    public List<ToolPromptVersion> findPromptVersions(Long promptId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM tool_prompt_versions
                WHERE prompt_id = ?
                ORDER BY id DESC
                """, promptVersionRowMapper, promptId);
    }

    public Optional<ToolPromptVersion> findPromptVersionById(Long promptVersionId) {
        List<ToolPromptVersion> versions = jdbcTemplate.query("""
                SELECT *
                FROM tool_prompt_versions
                WHERE id = ?
                """, promptVersionRowMapper, promptVersionId);
        return versions.stream().findFirst();
    }

    public void inactivatePromptVersions(Long promptId) {
        jdbcTemplate.update("""
                UPDATE tool_prompt_versions
                SET status = 'INACTIVE'
                WHERE prompt_id = ? AND status = 'ACTIVE'
                """, promptId);
    }

    public void activatePromptVersion(Long promptVersionId) {
        jdbcTemplate.update("""
                UPDATE tool_prompt_versions
                SET status = 'ACTIVE', published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, promptVersionId);
    }

    public void setPromptActiveVersion(Long promptId, Long promptVersionId) {
        jdbcTemplate.update("""
                UPDATE tool_prompts
                SET active_version_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, promptVersionId, promptId);
    }

    public boolean hasActivePromptVersion(Long toolId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM tool_prompts p
                JOIN tool_prompt_versions v ON v.id = p.active_version_id
                WHERE p.tool_id = ? AND p.status = 'ACTIVE' AND v.status = 'ACTIVE'
                """, Integer.class, toolId);
        return count != null && count > 0;
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

    public void insertSchemaFields(Long schemaId, List<ToolFieldItem> fields) {
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
}
