package com.aiminilab.aitoolmarket.admin.mapper;

import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse.ModelCostPoint;
import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.admin.entity.BillingUsageLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface BillingUsageLogMapper extends BaseMapper<BillingUsageLog> {

    @Select("""
            SELECT COALESCE(SUM(prompt_tokens), 0)
            FROM billing_usage_logs
            WHERE created_at >= #{startAt} AND created_at < #{endAt}
            """)
    long sumPromptTokens(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COALESCE(SUM(completion_tokens), 0)
            FROM billing_usage_logs
            WHERE created_at >= #{startAt} AND created_at < #{endAt}
            """)
    long sumCompletionTokens(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COALESCE(SUM(total_tokens), 0)
            FROM billing_usage_logs
            WHERE created_at >= #{startAt} AND created_at < #{endAt}
            """)
    long sumTotalTokens(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COALESCE(SUM(cost_amount), 0)
            FROM billing_usage_logs
            WHERE created_at >= #{startAt} AND created_at < #{endAt}
            """)
    BigDecimal sumCostAmount(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COALESCE(SUM(charged_credits), 0)
            FROM billing_usage_logs
            WHERE created_at >= #{startAt} AND created_at < #{endAt}
            """)
    long sumChargedCredits(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COUNT(*)
            FROM billing_usage_logs
            WHERE created_at >= #{startAt} AND created_at < #{endAt}
            """)
    long countUsage(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT provider, model_name, COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(cost_amount), 0) AS cost_amount,
                   COALESCE(SUM(charged_credits), 0) AS charged_credits
            FROM billing_usage_logs
            WHERE created_at >= #{startAt} AND created_at < #{endAt}
            GROUP BY provider, model_name
            ORDER BY cost_amount DESC, total_tokens DESC
            LIMIT 10
            """)
    @ConstructorArgs({
            @Arg(column = "provider", javaType = String.class),
            @Arg(column = "model_name", javaType = String.class),
            @Arg(column = "total_tokens", javaType = long.class),
            @Arg(column = "cost_amount", javaType = BigDecimal.class),
            @Arg(column = "charged_credits", javaType = long.class)
    })
    List<ModelCostPoint> modelCosts(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT id, source_type, source_id, user_id, model_config_id, provider, model_name,
                   prompt_tokens, completion_tokens, total_tokens, input_token_price_per_1k,
                   output_token_price_per_1k, input_token_price_per_1m, output_token_price_per_1m,
                   billing_unit, billable_units, unit_price, cost_amount, charged_credits, created_at
            FROM billing_usage_logs
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "source_type", javaType = String.class),
            @Arg(column = "source_id", javaType = Long.class),
            @Arg(column = "user_id", javaType = Long.class),
            @Arg(column = "model_config_id", javaType = Long.class),
            @Arg(column = "provider", javaType = String.class),
            @Arg(column = "model_name", javaType = String.class),
            @Arg(column = "prompt_tokens", javaType = Integer.class),
            @Arg(column = "completion_tokens", javaType = Integer.class),
            @Arg(column = "total_tokens", javaType = Integer.class),
            @Arg(column = "input_token_price_per_1k", javaType = BigDecimal.class),
            @Arg(column = "output_token_price_per_1k", javaType = BigDecimal.class),
            @Arg(column = "input_token_price_per_1m", javaType = BigDecimal.class),
            @Arg(column = "output_token_price_per_1m", javaType = BigDecimal.class),
            @Arg(column = "billing_unit", javaType = String.class),
            @Arg(column = "billable_units", javaType = Integer.class),
            @Arg(column = "unit_price", javaType = BigDecimal.class),
            @Arg(column = "cost_amount", javaType = BigDecimal.class),
            @Arg(column = "charged_credits", javaType = Integer.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class)
    })
    List<BillingUsageLogResponse> findLogs(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM billing_usage_logs")
    long countLogs();
}
