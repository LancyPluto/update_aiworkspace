package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.common.enums.ToolStatus;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

public interface ToolMapper extends BaseMapper<AiTool> {

    @Select("""
            <script>
            SELECT t.*, c.category_name, c.category_code,
                   COALESCE(m.display_name, m.model_name) AS model_config_name,
                   m.model_name
            FROM ai_tools t
            JOIN tool_categories c ON c.id = t.category_id
            LEFT JOIN agent_model_configs m ON m.id = t.model_config_id AND COALESCE(m.is_deleted, 0) = 0
            WHERE t.is_deleted = 0
            <if test="onlineOnly">
              AND t.status = 'ONLINE'
            </if>
            <if test="!onlineOnly and status != null and status.trim() != ''">
              AND t.status = #{status}
            </if>
            <if test="categoryId != null">
              AND t.category_id = #{categoryId}
            </if>
            <if test="keyword != null and keyword.trim() != ''">
              AND (
                LOWER(t.tool_code) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR LOWER(t.tool_name) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR LOWER(t.description) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            ORDER BY t.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AiTool> findTools(@Param("onlineOnly") boolean onlineOnly,
                           @Param("keyword") String keyword,
                           @Param("categoryId") Long categoryId,
                           @Param("status") String status,
                           @Param("limit") int limit,
                           @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ai_tools t
            JOIN tool_categories c ON c.id = t.category_id
            WHERE t.is_deleted = 0
            <if test="onlineOnly">
              AND t.status = 'ONLINE'
            </if>
            <if test="!onlineOnly and status != null and status.trim() != ''">
              AND t.status = #{status}
            </if>
            <if test="categoryId != null">
              AND t.category_id = #{categoryId}
            </if>
            <if test="keyword != null and keyword.trim() != ''">
              AND (
                LOWER(t.tool_code) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR LOWER(t.tool_name) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR LOWER(t.description) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            </script>
            """)
    long countTools(@Param("onlineOnly") boolean onlineOnly,
                    @Param("keyword") String keyword,
                    @Param("categoryId") Long categoryId,
                    @Param("status") String status);

    @Select("""
            SELECT t.*, c.category_name, c.category_code,
                   COALESCE(m.display_name, m.model_name) AS model_config_name,
                   m.model_name
            FROM ai_tools t
            JOIN tool_categories c ON c.id = t.category_id
            LEFT JOIN agent_model_configs m ON m.id = t.model_config_id AND COALESCE(m.is_deleted, 0) = 0
            WHERE t.id = #{toolId} AND t.is_deleted = 0
            LIMIT 1
            """)
    AiTool selectDetailById(@Param("toolId") Long toolId);

    default Optional<AiTool> findById(Long toolId) {
        return Optional.ofNullable(selectDetailById(toolId));
    }

    @Select("""
            SELECT t.*, c.category_name, c.category_code,
                   COALESCE(m.display_name, m.model_name) AS model_config_name,
                   m.model_name
            FROM ai_tools t
            JOIN tool_categories c ON c.id = t.category_id
            LEFT JOIN agent_model_configs m ON m.id = t.model_config_id AND COALESCE(m.is_deleted, 0) = 0
            WHERE t.tool_code = #{toolCode} AND t.status = 'ONLINE' AND t.is_deleted = 0
            LIMIT 1
            """)
    AiTool selectOnlineByCode(@Param("toolCode") String toolCode);

    default Optional<AiTool> findOnlineByCode(String toolCode) {
        return Optional.ofNullable(selectOnlineByCode(toolCode));
    }

    @Select("""
            SELECT COUNT(*)
            FROM ai_tools
            WHERE tool_code = #{toolCode} AND is_deleted = 0
            """)
    long countByCode(@Param("toolCode") String toolCode);

    default boolean existsByCode(String toolCode) {
        return countByCode(toolCode) > 0;
    }

    default Long insertTool(AiTool tool, Long operatorId) {
        tool.setStatus(ToolStatus.DRAFT.name());
        tool.setCreatedBy(operatorId);
        tool.setUpdatedBy(operatorId);
        tool.setDeleted(false);
        insert(tool);
        return tool.getId();
    }

    @Update("""
            UPDATE ai_tools
            SET tool_name = #{tool.toolName}, category_id = #{tool.categoryId},
                description = #{tool.description}, cover_url = #{tool.coverUrl},
                tool_type = #{tool.toolType},
                input_modality = #{tool.inputModality},
                output_modality = #{tool.outputModality},
                config_note = #{tool.configNote},
                estimated_credit_cost = #{tool.estimatedCreditCost},
                model_config_id = #{tool.modelConfigId},
                template_id = #{tool.templateId},
                execution_handler = #{tool.executionHandler},
                updated_by = #{operatorId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{toolId} AND is_deleted = 0
            """)
    void updateTool(@Param("toolId") Long toolId,
                    @Param("tool") AiTool tool,
                    @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE ai_tools
            SET status = #{status}, updated_by = #{operatorId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{toolId} AND is_deleted = 0
            """)
    void updateToolStatusValue(@Param("toolId") Long toolId,
                               @Param("status") String status,
                               @Param("operatorId") Long operatorId);

    default void updateToolStatus(Long toolId, ToolStatus status, Long operatorId) {
        updateToolStatusValue(toolId, status.name(), operatorId);
    }

    @Update("""
            UPDATE ai_tools
            SET is_deleted = 1,
                status = 'OFFLINE',
                updated_by = #{operatorId},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{toolId} AND is_deleted = 0
            """)
    int softDeleteTool(@Param("toolId") Long toolId,
                       @Param("operatorId") Long operatorId);
}
