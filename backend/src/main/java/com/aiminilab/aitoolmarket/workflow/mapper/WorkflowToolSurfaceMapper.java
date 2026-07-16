package com.aiminilab.aitoolmarket.workflow.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WorkflowToolSurfaceMapper {

    @Select("""
            <script>
            SELECT t.id,
                   t.tool_code AS toolCode,
                   t.tool_name AS toolName,
                   t.description,
                   c.category_name AS categoryName,
                   t.cover_url AS coverUrl,
                   t.status,
                   t.estimated_credit_cost AS estimatedCreditCost,
                   t.minimum_required_credits AS minimumRequiredCredits,
                   t.billing_mode AS billingMode,
                   v.config_json AS versionConfigJson,
                   w.id AS workflowId,
                   w.published_version_id AS publishedVersionId
            FROM ai_tools t
            JOIN tool_workflows w
              ON w.tool_id = t.id
             AND w.execution_enabled = 1
             AND w.published_version_id IS NOT NULL
            JOIN tool_workflow_versions v
              ON v.id = w.published_version_id
             AND v.workflow_id = w.id
            LEFT JOIN tool_categories c ON c.id = t.category_id
            WHERE t.status = 'ONLINE'
              AND COALESCE(t.is_deleted, 0) = 0
              AND t.execution_mode = 'WORKFLOW'
              AND t.agent_surface_enabled = 1
              AND w.id = (
                  SELECT cw.id
                  FROM tool_workflows cw
                  JOIN tool_workflow_versions cv
                    ON cv.id = cw.published_version_id
                   AND cv.workflow_id = cw.id
                  WHERE cw.tool_id = t.id
                    AND cw.execution_enabled = 1
                    AND cw.published_version_id IS NOT NULL
                  ORDER BY CASE WHEN cw.workflow_name = 'default' THEN 0 ELSE 1 END, cw.id DESC
                  LIMIT 1
              )
            <if test="keyword != null and keyword != ''">
              AND (
                    LOWER(COALESCE(t.tool_code, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(t.tool_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(t.description, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(c.category_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            <if test="category != null and category != ''">
              AND (
                    LOWER(COALESCE(c.category_code, '')) = LOWER(#{category})
                 OR LOWER(COALESCE(c.category_name, '')) = LOWER(#{category})
              )
            </if>
            ORDER BY t.id DESC, w.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<WorkflowToolSurfaceRow> selectPage(@Param("keyword") String keyword,
                                            @Param("category") String category,
                                            @Param("limit") int limit,
                                            @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ai_tools t
            JOIN tool_workflows w
              ON w.tool_id = t.id
             AND w.execution_enabled = 1
             AND w.published_version_id IS NOT NULL
            JOIN tool_workflow_versions v
              ON v.id = w.published_version_id
             AND v.workflow_id = w.id
            LEFT JOIN tool_categories c ON c.id = t.category_id
            WHERE t.status = 'ONLINE'
              AND COALESCE(t.is_deleted, 0) = 0
              AND t.execution_mode = 'WORKFLOW'
              AND t.agent_surface_enabled = 1
              AND w.id = (
                  SELECT cw.id
                  FROM tool_workflows cw
                  JOIN tool_workflow_versions cv
                    ON cv.id = cw.published_version_id
                   AND cv.workflow_id = cw.id
                  WHERE cw.tool_id = t.id
                    AND cw.execution_enabled = 1
                    AND cw.published_version_id IS NOT NULL
                  ORDER BY CASE WHEN cw.workflow_name = 'default' THEN 0 ELSE 1 END, cw.id DESC
                  LIMIT 1
              )
            <if test="keyword != null and keyword != ''">
              AND (
                    LOWER(COALESCE(t.tool_code, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(t.tool_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(t.description, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(c.category_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            <if test="category != null and category != ''">
              AND (
                    LOWER(COALESCE(c.category_code, '')) = LOWER(#{category})
                 OR LOWER(COALESCE(c.category_name, '')) = LOWER(#{category})
              )
            </if>
            </script>
            """)
    long count(@Param("keyword") String keyword, @Param("category") String category);

    @Select("""
            SELECT t.id,
                   t.tool_code AS toolCode,
                   t.tool_name AS toolName,
                   t.description,
                   c.category_name AS categoryName,
                   t.cover_url AS coverUrl,
                   t.status,
                   t.estimated_credit_cost AS estimatedCreditCost,
                   t.minimum_required_credits AS minimumRequiredCredits,
                   t.billing_mode AS billingMode,
                   v.config_json AS versionConfigJson,
                   w.id AS workflowId,
                   w.published_version_id AS publishedVersionId
            FROM ai_tools t
            JOIN tool_workflows w
              ON w.tool_id = t.id
             AND w.execution_enabled = 1
             AND w.published_version_id IS NOT NULL
            JOIN tool_workflow_versions v
              ON v.id = w.published_version_id
             AND v.workflow_id = w.id
            LEFT JOIN tool_categories c ON c.id = t.category_id
            WHERE t.tool_code = #{toolCode}
              AND t.status = 'ONLINE'
              AND COALESCE(t.is_deleted, 0) = 0
              AND t.execution_mode = 'WORKFLOW'
              AND t.agent_surface_enabled = 1
              AND w.id = (
                  SELECT cw.id
                  FROM tool_workflows cw
                  JOIN tool_workflow_versions cv
                    ON cv.id = cw.published_version_id
                   AND cv.workflow_id = cw.id
                  WHERE cw.tool_id = t.id
                    AND cw.execution_enabled = 1
                    AND cw.published_version_id IS NOT NULL
                  ORDER BY CASE WHEN cw.workflow_name = 'default' THEN 0 ELSE 1 END, cw.id DESC
                  LIMIT 1
              )
            ORDER BY t.id DESC, w.id DESC
            LIMIT 1
            """)
    WorkflowToolSurfaceRow selectCanonicalByToolCode(@Param("toolCode") String toolCode);
}
