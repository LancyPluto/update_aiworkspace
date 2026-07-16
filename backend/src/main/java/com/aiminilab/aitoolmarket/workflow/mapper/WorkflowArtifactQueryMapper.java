package com.aiminilab.aitoolmarket.workflow.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WorkflowArtifactQueryMapper {

    @Select("""
            <script>
            SELECT id,
                   task_id AS taskId,
                   resource_type AS resourceType,
                   content_text AS contentText,
                   sort_order AS sortOrder
            FROM ai_result_resources
            WHERE task_id IN
            <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
              #{taskId}
            </foreach>
            ORDER BY task_id, sort_order, id
            </script>
            """)
    List<WorkflowArtifactRow> selectByTaskIds(@Param("taskIds") List<Long> taskIds);
}
