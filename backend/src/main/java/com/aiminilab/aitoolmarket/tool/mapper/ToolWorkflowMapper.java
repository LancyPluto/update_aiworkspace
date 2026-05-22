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

    @Select("SELECT * FROM tool_workflows WHERE tool_id = #{toolId} AND workflow_name = #{name} LIMIT 1")
    ToolWorkflow selectByToolIdAndName(@Param("toolId") Long toolId, @Param("name") String name);

    default Long insertWorkflow(ToolWorkflow workflow, Long operatorId) {
        workflow.setVersion(1);
        workflow.setStatus("DRAFT");
        workflow.setCreatedBy(operatorId);
        workflow.setUpdatedBy(operatorId);
        insert(workflow);
        return workflow.getId();
    }

    @Update("""
            UPDATE tool_workflows
            SET nodes_json = #{nodesJson}, edges_json = #{edgesJson},
                groups_json = #{groupsJson}, config_json = #{configJson},
                version = #{newVersion}, status = 'DRAFT',
                updated_by = #{operatorId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
            """)
    void updateWorkflowContent(@Param("workflowId") Long workflowId,
                               @Param("nodesJson") String nodesJson,
                               @Param("edgesJson") String edgesJson,
                               @Param("groupsJson") String groupsJson,
                               @Param("configJson") String configJson,
                               @Param("newVersion") int newVersion,
                               @Param("operatorId") Long operatorId);
}
