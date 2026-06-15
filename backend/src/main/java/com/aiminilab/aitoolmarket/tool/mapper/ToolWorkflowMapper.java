package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.Optional;

public interface ToolWorkflowMapper extends BaseMapper<ToolWorkflow> {

    @Select("SELECT * FROM tool_workflows WHERE tool_id = #{toolId} LIMIT 1")
    ToolWorkflow selectByToolId(@Param("toolId") Long toolId);

    default Optional<ToolWorkflow> findByToolId(Long toolId) {
        return Optional.ofNullable(selectByToolId(toolId));
    }

    @Select("SELECT * FROM tool_workflows WHERE tool_id = #{toolId} AND status = 'PUBLISHED' LIMIT 1")
    ToolWorkflow selectPublishedByToolId(@Param("toolId") Long toolId);

    default Optional<ToolWorkflow> findPublishedByToolId(Long toolId) {
        return Optional.ofNullable(selectPublishedByToolId(toolId));
    }

    @Select("SELECT * FROM tool_workflows WHERE tool_id = #{toolId} AND workflow_name = #{name} LIMIT 1")
    ToolWorkflow selectByToolIdAndName(@Param("toolId") Long toolId, @Param("name") String name);

    default Long insertWorkflow(ToolWorkflow workflow, Long operatorId) {
        workflow.setVersion(1);
        if (workflow.getStatus() == null || workflow.getStatus().isBlank()) {
            workflow.setStatus("DRAFT");
        }
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
                version = #{newVersion}, status = COALESCE(#{status}, status),
                updated_by = #{operatorId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
            """)
    void updateWorkflowContent(@Param("workflowId") Long workflowId,
                               @Param("nodesJson") String nodesJson,
                               @Param("edgesJson") String edgesJson,
                               @Param("groupsJson") String groupsJson,
                               @Param("configJson") String configJson,
                               @Param("newVersion") int newVersion,
                               @Param("status") String status,
                               @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE tool_workflows
            SET status = #{status}, updated_by = #{operatorId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
            """)
    void updateWorkflowStatus(@Param("workflowId") Long workflowId,
                              @Param("status") String status,
                              @Param("operatorId") Long operatorId);
}
