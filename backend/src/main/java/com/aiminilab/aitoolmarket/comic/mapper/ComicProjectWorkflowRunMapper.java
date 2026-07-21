package com.aiminilab.aitoolmarket.comic.mapper;

import com.aiminilab.aitoolmarket.comic.entity.ComicProjectWorkflowRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ComicProjectWorkflowRunMapper extends BaseMapper<ComicProjectWorkflowRun> {
    @Select("SELECT * FROM comic_project_workflow_runs WHERE root_task_id = #{rootTaskId} AND user_id = #{userId} LIMIT 1")
    ComicProjectWorkflowRun selectByRootTask(@Param("rootTaskId") Long rootTaskId,
                                             @Param("userId") Long userId);

    @Select("SELECT * FROM comic_project_workflow_runs WHERE root_task_id = #{rootTaskId} LIMIT 1")
    ComicProjectWorkflowRun selectByRootTaskInternal(@Param("rootTaskId") Long rootTaskId);

    @Select("SELECT * FROM comic_project_workflow_runs WHERE workflow_run_id = #{workflowRunId} LIMIT 1")
    ComicProjectWorkflowRun selectByWorkflowRunInternal(@Param("workflowRunId") Long workflowRunId);
}
