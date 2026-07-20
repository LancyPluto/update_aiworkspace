package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.Optional;
import java.util.List;

public interface ToolWorkflowMapper extends BaseMapper<ToolWorkflow> {

    @Select("SELECT * FROM tool_workflows WHERE tool_id = #{toolId} LIMIT 1")
    ToolWorkflow selectByToolId(@Param("toolId") Long toolId);

    default Optional<ToolWorkflow> findByToolId(Long toolId) {
        return Optional.ofNullable(selectByToolId(toolId));
    }

    @Select("""
            SELECT w.*
            FROM tool_workflows w
            JOIN tool_workflow_versions v
              ON v.id = w.published_version_id
             AND v.workflow_id = w.id
            WHERE w.tool_id = #{toolId}
              AND w.execution_enabled = 1
              AND w.published_version_id IS NOT NULL
            ORDER BY CASE WHEN w.workflow_name = 'default' THEN 0 ELSE 1 END,
                     w.id DESC
            LIMIT 1
            """)
    ToolWorkflow selectExecutableCanonicalByToolId(@Param("toolId") Long toolId);

    @Select("""
            <script>
            SELECT DISTINCT w.tool_id
            FROM tool_workflows w
            JOIN tool_workflow_versions v
              ON v.id = w.published_version_id
             AND v.workflow_id = w.id
            WHERE w.execution_enabled = 1
              AND w.published_version_id IS NOT NULL
              AND w.tool_id IN
              <foreach collection="toolIds" item="toolId" open="(" separator="," close=")">
                #{toolId}
              </foreach>
            </script>
            """)
    List<Long> selectExecutableToolIds(@Param("toolIds") List<Long> toolIds);

    @Select("""
            SELECT w.*
            FROM ai_tools t
            JOIN tool_workflows w
              ON w.tool_id = t.id
            JOIN tool_workflow_versions v
              ON v.id = w.published_version_id
             AND v.workflow_id = w.id
            WHERE t.id = #{toolId}
              AND t.status = 'ONLINE'
              AND COALESCE(t.is_deleted, 0) = 0
              AND t.execution_mode = 'WORKFLOW'
              AND t.billing_mode = 'WORKFLOW_STEP'
              AND t.agent_surface_enabled = 1
              AND w.execution_enabled = 1
              AND w.published_version_id IS NOT NULL
            ORDER BY CASE WHEN w.workflow_name = 'default' THEN 0 ELSE 1 END,
                     w.id DESC
            LIMIT 1
            FOR UPDATE
            """)
    ToolWorkflow selectCanonicalPublishedByToolId(@Param("toolId") Long toolId);

    @Select("""
            SELECT w.id, w.tool_id, w.workflow_name,
                   v.nodes_json, v.edges_json, v.groups_json, v.config_json,
                   v.version, 'PUBLISHED' AS status,
                   w.draft_revision, w.published_version_id, w.execution_enabled,
                   w.created_by, w.updated_by, w.created_at, w.updated_at
            FROM tool_workflows w
            JOIN tool_workflow_versions v ON v.id = w.published_version_id
            WHERE w.tool_id = #{toolId} AND w.execution_enabled = 1
            LIMIT 1
            """)
    ToolWorkflow selectPublishedByToolId(@Param("toolId") Long toolId);

    default Optional<ToolWorkflow> findPublishedByToolId(Long toolId) {
        return Optional.ofNullable(selectPublishedByToolId(toolId));
    }

    @Select("SELECT * FROM tool_workflows WHERE tool_id = #{toolId} AND workflow_name = #{name} LIMIT 1")
    ToolWorkflow selectByToolIdAndName(@Param("toolId") Long toolId, @Param("name") String name);

    default Long insertWorkflow(ToolWorkflow workflow, Long operatorId) {
        workflow.setVersion(1);
        workflow.setStatus("DRAFT");
        workflow.setDraftRevision(1L);
        workflow.setExecutionEnabled(false);
        workflow.setCreatedBy(operatorId);
        workflow.setUpdatedBy(operatorId);
        // createdAt/updatedAt 标注了 @TableField(fill=INSERT)，但项目未注册 MetaObjectHandler，
        // MyBatis-Plus 会把它们当作 NULL 写入（列为 NOT NULL）→ "created_at cannot be null"。
        // 显式赋值以兜底（配置包导入工作流即依赖此路径）。
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (workflow.getCreatedAt() == null) {
            workflow.setCreatedAt(now);
        }
        if (workflow.getUpdatedAt() == null) {
            workflow.setUpdatedAt(now);
        }
        insert(workflow);
        return workflow.getId();
    }

    @Update("""
            UPDATE tool_workflows
            SET nodes_json = #{nodesJson}, edges_json = #{edgesJson},
                groups_json = #{groupsJson}, config_json = #{configJson},
                draft_revision = draft_revision + 1,
                updated_by = #{operatorId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId} AND draft_revision = #{expectedDraftRevision}
            """)
    int updateDraftIfRevision(@Param("workflowId") Long workflowId,
                              @Param("nodesJson") String nodesJson,
                              @Param("edgesJson") String edgesJson,
                              @Param("groupsJson") String groupsJson,
                              @Param("configJson") String configJson,
                              @Param("expectedDraftRevision") Long expectedDraftRevision,
                              @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE tool_workflows
            SET published_version_id = #{publishedVersionId},
                execution_enabled = 1,
                status = 'PUBLISHED',
                version = #{version},
                updated_by = #{operatorId},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId} AND draft_revision = #{expectedDraftRevision}
            """)
    int bindPublishedVersion(@Param("workflowId") Long workflowId,
                             @Param("publishedVersionId") Long publishedVersionId,
                             @Param("version") int version,
                             @Param("expectedDraftRevision") Long expectedDraftRevision,
                             @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE tool_workflows
            SET execution_enabled = 0,
                status = 'DRAFT',
                updated_by = #{operatorId},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
            """)
    int disableExecution(@Param("workflowId") Long workflowId,
                         @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE tool_workflows
            SET execution_enabled = 0,
                updated_by = #{operatorId},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
            """)
    int disableExecutionPreservingPublication(@Param("workflowId") Long workflowId,
                                              @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE tool_workflows
            SET nodes_json = #{nodesJson}, edges_json = #{edgesJson},
                groups_json = #{groupsJson}, config_json = #{configJson},
                draft_revision = draft_revision + 1,
                updated_by = #{operatorId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId} AND draft_revision = #{expectedDraftRevision}
            """)
    int restoreDraftIfRevision(@Param("workflowId") Long workflowId,
                               @Param("nodesJson") String nodesJson,
                               @Param("edgesJson") String edgesJson,
                               @Param("groupsJson") String groupsJson,
                               @Param("configJson") String configJson,
                               @Param("expectedDraftRevision") Long expectedDraftRevision,
                               @Param("operatorId") Long operatorId);
}
