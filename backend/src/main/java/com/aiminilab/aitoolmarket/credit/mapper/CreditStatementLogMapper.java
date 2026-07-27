package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.dto.CreditStatementLogResponse;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface CreditStatementLogMapper {

    String NORMALIZED_VISIBLE_LOGS = """
            SELECT cl.id,
                   cl.user_id,
                   CASE
                     WHEN NULLIF(TRIM(cl.source_type), '') IS NOT NULL AND cl.source_ref IS NOT NULL
                       THEN UPPER(TRIM(cl.source_type))
                     WHEN cl.task_id IS NOT NULL THEN 'TASK'
                     WHEN cl.agent_run_id IS NOT NULL THEN 'AGENT_RUN'
                     ELSE NULL
                   END AS source_type,
                   CASE
                     WHEN NULLIF(TRIM(cl.source_type), '') IS NOT NULL AND cl.source_ref IS NOT NULL
                       THEN cl.source_ref
                     WHEN cl.task_id IS NOT NULL THEN cl.task_id
                     WHEN cl.agent_run_id IS NOT NULL THEN cl.agent_run_id
                     ELSE NULL
                   END AS source_ref,
                   cl.log_type,
                   cl.amount,
                   cl.created_at
            FROM credit_logs cl
            WHERE cl.user_id = #{userId}
              AND cl.log_type NOT IN ('FREEZE', 'RELEASE')
              AND cl.amount <> 0
            """;

    String STATEMENT_ENTRIES = """
            SELECT MAX(sourced.id) AS id,
                   sourced.user_id,
                   sourced.source_type,
                   sourced.source_ref,
                   'DEDUCT' AS log_type,
                   SUM(sourced.amount) AS amount,
                   MAX(sourced.created_at) AS created_at
            FROM (
            """ + NORMALIZED_VISIBLE_LOGS + """
            ) sourced
            WHERE sourced.log_type = 'DEDUCT'
              AND sourced.source_type IS NOT NULL
              AND sourced.source_ref IS NOT NULL
            GROUP BY sourced.user_id, sourced.source_type, sourced.source_ref
            HAVING SUM(sourced.amount) <> 0
            UNION ALL
            SELECT ordinary.id,
                   ordinary.user_id,
                   ordinary.source_type,
                   ordinary.source_ref,
                   ordinary.log_type,
                   ordinary.amount,
                   ordinary.created_at
            FROM (
            """ + NORMALIZED_VISIBLE_LOGS + """
            ) ordinary
            WHERE NOT (
                ordinary.log_type = 'DEDUCT'
                AND ordinary.source_type IS NOT NULL
                AND ordinary.source_ref IS NOT NULL
            )
            """;

    @Select("""
            SELECT COUNT(*)
            FROM (
            """ + STATEMENT_ENTRIES + """
            ) statement_count
            """)
    long countStatementLogs(@Param("userId") Long userId);

    @Select("""
            SELECT page.id,
                   page.user_id,
                   page.source_type,
                   page.source_ref,
                   page.log_type,
                   page.amount,
                   latest_credit.reason,
                   page.created_at,
                   task.task_no,
                   COALESCE(task_tool.tool_name, workflow_tool.tool_name) AS tool_name,
                   agent_run.id AS agent_run_id,
                   agent_run.intent AS agent_intent,
                   workflow_run.id AS workflow_run_id,
                   workflow_run.workflow_id AS workflow_id,
                   CASE
                     WHEN workflow_def.id IS NULL THEN NULL
                     WHEN NULLIF(TRIM(workflow_def.workflow_name), '') IS NULL
                          OR LOWER(TRIM(workflow_def.workflow_name)) = 'default'
                       THEN COALESCE(workflow_tool.tool_name, workflow_def.workflow_name)
                     ELSE workflow_def.workflow_name
                   END AS workflow_name,
                   workflow_step.node_id AS workflow_node_id,
                   COALESCE(NULLIF(TRIM(workflow_step.node_title), ''), workflow_step.node_id)
                     AS workflow_step_name,
                   COALESCE(usage_log.model_name, agent_run.model_name) AS model_name,
                   COALESCE(usage_log.provider, agent_run.model_provider_code) AS provider
            FROM (
                SELECT statement_page.*
                FROM (
            """ + STATEMENT_ENTRIES + """
                ) statement_page
                ORDER BY statement_page.created_at DESC, statement_page.id DESC
                LIMIT #{limit} OFFSET #{offset}
            ) page
            LEFT JOIN credit_logs latest_credit
              ON latest_credit.id = page.id
             AND latest_credit.user_id = page.user_id
            LEFT JOIN ai_tasks task
              ON page.source_type = 'TASK'
             AND page.source_ref = task.id
             AND task.user_id = page.user_id
            LEFT JOIN ai_tools task_tool ON task.tool_id = task_tool.id
            LEFT JOIN agent_runs agent_run
              ON page.source_type = 'AGENT_RUN'
             AND page.source_ref = agent_run.id
             AND agent_run.user_id = page.user_id
            LEFT JOIN workflow_run_steps workflow_step
              ON page.source_type = 'WORKFLOW_STEP'
             AND page.source_ref = workflow_step.id
             AND EXISTS (
                 SELECT 1
                 FROM workflow_runs owned_workflow_run
                 WHERE owned_workflow_run.id = workflow_step.run_id
                   AND owned_workflow_run.user_id = page.user_id
             )
            LEFT JOIN workflow_runs workflow_run
              ON workflow_step.run_id = workflow_run.id
             AND workflow_run.user_id = page.user_id
            LEFT JOIN tool_workflows workflow_def ON workflow_run.workflow_id = workflow_def.id
            LEFT JOIN ai_tools workflow_tool ON workflow_run.tool_id = workflow_tool.id
            LEFT JOIN billing_usage_logs usage_log
              ON usage_log.id = (
                  SELECT candidate.id
                  FROM billing_usage_logs candidate
                  WHERE candidate.user_id = page.user_id
                    AND candidate.source_type = page.source_type
                    AND candidate.source_id = page.source_ref
                  ORDER BY CASE WHEN candidate.charged_credits > 0 THEN 0 ELSE 1 END,
                           candidate.id DESC
                  LIMIT 1
              )
            ORDER BY page.created_at DESC, page.id DESC
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "user_id", javaType = Long.class),
            @Arg(column = "source_type", javaType = String.class),
            @Arg(column = "source_ref", javaType = Long.class),
            @Arg(column = "log_type", javaType = String.class),
            @Arg(column = "amount", javaType = Long.class),
            @Arg(column = "reason", javaType = String.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "task_no", javaType = String.class),
            @Arg(column = "tool_name", javaType = String.class),
            @Arg(column = "agent_run_id", javaType = Long.class),
            @Arg(column = "agent_intent", javaType = String.class),
            @Arg(column = "workflow_run_id", javaType = Long.class),
            @Arg(column = "workflow_id", javaType = Long.class),
            @Arg(column = "workflow_name", javaType = String.class),
            @Arg(column = "workflow_node_id", javaType = String.class),
            @Arg(column = "workflow_step_name", javaType = String.class),
            @Arg(column = "model_name", javaType = String.class),
            @Arg(column = "provider", javaType = String.class)
    })
    List<CreditStatementLogResponse> findStatementLogs(@Param("userId") Long userId,
                                                        @Param("limit") int limit,
                                                        @Param("offset") int offset);
}
