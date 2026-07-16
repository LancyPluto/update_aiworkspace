package com.aiminilab.aitoolmarket.tool.mapper;

import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ToolWorkflowVersionMapper extends BaseMapper<ToolWorkflowVersion> {

    @Select("SELECT * FROM tool_workflow_versions WHERE workflow_id = #{workflowId} ORDER BY version DESC LIMIT #{limit} OFFSET #{offset}")
    List<ToolWorkflowVersion> selectByWorkflowId(@Param("workflowId") Long workflowId,
                                                  @Param("limit") int limit,
                                                  @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM tool_workflow_versions WHERE workflow_id = #{workflowId}")
    long countByWorkflowId(@Param("workflowId") Long workflowId);

    @Select("SELECT * FROM tool_workflow_versions WHERE workflow_id = #{workflowId} AND version = #{version} LIMIT 1")
    ToolWorkflowVersion selectByWorkflowIdAndVersion(@Param("workflowId") Long workflowId,
                                                       @Param("version") int version);

    @Select("SELECT COALESCE(MAX(version), 0) FROM tool_workflow_versions WHERE workflow_id = #{workflowId}")
    int selectMaxVersion(@Param("workflowId") Long workflowId);

    @Select("SELECT * FROM tool_workflow_versions WHERE workflow_id = #{workflowId} AND dsl_hash = #{dslHash} LIMIT 1")
    ToolWorkflowVersion selectByWorkflowIdAndDslHash(@Param("workflowId") Long workflowId,
                                                      @Param("dslHash") String dslHash);
}
