package com.aiminilab.aitoolmarket.admin.mapper;

import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.TaskTrendPoint;
import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse.ToolUsagePoint;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Select;

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
}
