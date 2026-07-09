package com.aiminilab.aitoolmarket.admin.mapper;

import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.TaskTrendPoint;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.BusinessMetrics;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.BusinessTrendRawPoint;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.ToolContributionRawPoint;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.ToolUsagePoint;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface DashboardMapper {

    @Select("""
            SELECT DATE_FORMAT(created_at, '%Y-%m') AS month, COUNT(*) AS task_count
            FROM ai_tasks
            WHERE created_at >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 6 MONTH), '%Y-%m-01')
            GROUP BY DATE_FORMAT(created_at, '%Y-%m')
            ORDER BY month ASC
            """)
    @ConstructorArgs({
            @Arg(column = "month", javaType = String.class),
            @Arg(column = "task_count", javaType = long.class)
    })
    List<TaskTrendPoint> selectTaskTrend();

    @Select("""
            SELECT tool.tool_name AS tool_name, COUNT(task.id) AS task_count
            FROM ai_tasks task
            JOIN ai_tools tool ON tool.id = task.tool_id
            WHERE tool.is_deleted = 0
            GROUP BY tool.id, tool.tool_name
            ORDER BY task_count DESC, tool.id DESC
            LIMIT 5
            """)
    @ConstructorArgs({
            @Arg(column = "tool_name", javaType = String.class),
            @Arg(column = "task_count", javaType = long.class)
    })
    List<ToolUsagePoint> selectPopularTools();

    @Select("""
            SELECT COALESCE(SUM(amount), 0)
            FROM credit_logs
            WHERE log_type IN ('CONSUME', 'SETTLE')
            """)
    long sumConsumedCredits();

    @Select("""
            SELECT
              (SELECT COALESCE(SUM(price_amount), 0)
               FROM credit_recharge_orders
               WHERE status IN ('PAID', 'CREDITED')
                 AND paid_at >= #{startAt}
                 AND paid_at < #{endAt}) AS recharge_revenue_amount,
              (SELECT COALESCE(SUM(charged_credits), 0)
               FROM billing_usage_logs
               WHERE created_at >= #{startAt}
                 AND created_at < #{endAt}) AS charged_credits,
              (SELECT COALESCE(SUM(cost_amount), 0)
               FROM billing_usage_logs
               WHERE created_at >= #{startAt}
                 AND created_at < #{endAt}) AS vendor_cost_amount,
              (SELECT COUNT(*)
               FROM ai_tasks
               WHERE created_at >= #{startAt}
                 AND created_at < #{endAt}) AS task_total,
              (SELECT COUNT(*)
               FROM ai_tasks
               WHERE created_at >= #{startAt}
                 AND created_at < #{endAt}
                 AND status = 'SUCCESS') AS success_task_total,
              (SELECT COUNT(*)
               FROM ai_tasks
               WHERE created_at >= #{startAt}
                 AND created_at < #{endAt}
                 AND status IN ('FAILED', 'TIMEOUT', 'CANCELLED')) AS failed_task_total,
              (SELECT COUNT(*)
               FROM ai_tasks
               WHERE created_at >= #{startAt}
                 AND created_at < #{endAt}
                 AND status IN ('QUEUED', 'PROCESSING', 'RETRYING', 'AWAITING_USER')) AS processing_task_total,
              (SELECT COUNT(*)
               FROM users
               WHERE is_deleted = 0
                 AND created_at >= #{startAt}
                 AND created_at < #{endAt}) AS new_user_count,
              (SELECT COUNT(*)
               FROM users
               WHERE is_deleted = 0) AS total_user_count,
              (SELECT COUNT(*)
               FROM ai_tools
               WHERE is_deleted = 0
                 AND status = 'ONLINE') AS online_tool_count,
              (SELECT COUNT(*)
               FROM ai_tools
               WHERE is_deleted = 0
                 AND status <> 'ONLINE') AS draft_tool_count
            """)
    @ConstructorArgs({
            @Arg(column = "recharge_revenue_amount", javaType = BigDecimal.class),
            @Arg(column = "charged_credits", javaType = long.class),
            @Arg(column = "vendor_cost_amount", javaType = BigDecimal.class),
            @Arg(column = "task_total", javaType = long.class),
            @Arg(column = "success_task_total", javaType = long.class),
            @Arg(column = "failed_task_total", javaType = long.class),
            @Arg(column = "processing_task_total", javaType = long.class),
            @Arg(column = "new_user_count", javaType = long.class),
            @Arg(column = "total_user_count", javaType = long.class),
            @Arg(column = "online_tool_count", javaType = long.class),
            @Arg(column = "draft_tool_count", javaType = long.class)
    })
    BusinessMetrics selectBusinessMetrics(@Param("startAt") LocalDateTime startAt,
                                          @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT metric_day,
                   COALESCE(SUM(recharge_revenue_amount), 0) AS recharge_revenue_amount,
                   COALESCE(SUM(charged_credits), 0) AS charged_credits,
                   COALESCE(SUM(vendor_cost_amount), 0) AS vendor_cost_amount,
                   COALESCE(SUM(task_total), 0) AS task_total
            FROM (
              SELECT CAST(paid_at AS DATE) AS metric_day,
                     COALESCE(SUM(price_amount), 0) AS recharge_revenue_amount,
                     0 AS charged_credits,
                     0 AS vendor_cost_amount,
                     0 AS task_total
              FROM credit_recharge_orders
              WHERE status IN ('PAID', 'CREDITED')
                AND paid_at >= #{startAt}
                AND paid_at < #{endAt}
              GROUP BY CAST(paid_at AS DATE)
              UNION ALL
              SELECT CAST(created_at AS DATE) AS metric_day,
                     0 AS recharge_revenue_amount,
                     COALESCE(SUM(charged_credits), 0) AS charged_credits,
                     COALESCE(SUM(cost_amount), 0) AS vendor_cost_amount,
                     0 AS task_total
              FROM billing_usage_logs
              WHERE created_at >= #{startAt}
                AND created_at < #{endAt}
              GROUP BY CAST(created_at AS DATE)
              UNION ALL
              SELECT CAST(created_at AS DATE) AS metric_day,
                     0 AS recharge_revenue_amount,
                     0 AS charged_credits,
                     0 AS vendor_cost_amount,
                     COUNT(*) AS task_total
              FROM ai_tasks
              WHERE created_at >= #{startAt}
                AND created_at < #{endAt}
              GROUP BY CAST(created_at AS DATE)
            ) daily
            GROUP BY metric_day
            ORDER BY metric_day ASC
            """)
    @ConstructorArgs({
            @Arg(column = "metric_day", javaType = LocalDate.class),
            @Arg(column = "recharge_revenue_amount", javaType = BigDecimal.class),
            @Arg(column = "charged_credits", javaType = long.class),
            @Arg(column = "vendor_cost_amount", javaType = BigDecimal.class),
            @Arg(column = "task_total", javaType = long.class)
    })
    List<BusinessTrendRawPoint> selectBusinessTrend(@Param("startAt") LocalDateTime startAt,
                                                    @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT tool.tool_name AS tool_name,
                   COUNT(task.id) AS task_total,
                   SUM(CASE WHEN task.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_task_total,
                   SUM(CASE WHEN task.status IN ('FAILED', 'TIMEOUT', 'CANCELLED') THEN 1 ELSE 0 END) AS failed_task_total,
                   COALESCE(SUM(billing.charged_credits), 0) AS charged_credits,
                   COALESCE(SUM(billing.vendor_cost_amount), 0) AS vendor_cost_amount
            FROM ai_tasks task
            JOIN ai_tools tool ON tool.id = task.tool_id
            LEFT JOIN (
              SELECT source_id,
                     COALESCE(SUM(charged_credits), 0) AS charged_credits,
                     COALESCE(SUM(cost_amount), 0) AS vendor_cost_amount
              FROM billing_usage_logs
              WHERE source_type = 'TASK'
                AND created_at >= #{startAt}
                AND created_at < #{endAt}
              GROUP BY source_id
            ) billing ON billing.source_id = task.id
            WHERE task.created_at >= #{startAt}
              AND task.created_at < #{endAt}
              AND tool.is_deleted = 0
            GROUP BY tool.id, tool.tool_name
            ORDER BY charged_credits DESC, task_total DESC, tool.id DESC
            LIMIT 8
            """)
    @ConstructorArgs({
            @Arg(column = "tool_name", javaType = String.class),
            @Arg(column = "task_total", javaType = long.class),
            @Arg(column = "success_task_total", javaType = long.class),
            @Arg(column = "failed_task_total", javaType = long.class),
            @Arg(column = "charged_credits", javaType = long.class),
            @Arg(column = "vendor_cost_amount", javaType = BigDecimal.class)
    })
    List<ToolContributionRawPoint> selectToolContributions(@Param("startAt") LocalDateTime startAt,
                                                           @Param("endAt") LocalDateTime endAt);
}
