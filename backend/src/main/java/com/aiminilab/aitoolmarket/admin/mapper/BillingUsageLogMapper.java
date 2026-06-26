package com.aiminilab.aitoolmarket.admin.mapper;

import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse.DailyCostPoint;
import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse.ModalityCostPoint;
import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse.ModelCostPoint;
import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse.UserCostPoint;
import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.admin.entity.BillingUsageLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface BillingUsageLogMapper extends BaseMapper<BillingUsageLog> {

    String FILTER = """
            <if test="startAt != null">AND created_at &gt;= #{startAt}</if>
            <if test="endAt != null">AND created_at &lt; #{endAt}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
            <if test="modelConfigId != null">AND model_config_id = #{modelConfigId}</if>
            <if test="provider != null">AND provider = #{provider}</if>
            <if test="modelName != null">AND LOWER(model_name) LIKE CONCAT('%', LOWER(#{modelName}), '%')</if>
            <if test="sourceType != null">AND source_type = #{sourceType}</if>
            <if test="sourceId != null">AND source_id = #{sourceId}</if>
            """;

    String ALIASED_FILTER = """
            <if test="startAt != null">AND l.created_at &gt;= #{startAt}</if>
            <if test="endAt != null">AND l.created_at &lt; #{endAt}</if>
            <if test="userId != null">AND l.user_id = #{userId}</if>
            <if test="modelConfigId != null">AND l.model_config_id = #{modelConfigId}</if>
            <if test="provider != null">AND l.provider = #{provider}</if>
            <if test="modelName != null">AND LOWER(l.model_name) LIKE CONCAT('%', LOWER(#{modelName}), '%')</if>
            <if test="sourceType != null">AND l.source_type = #{sourceType}</if>
            <if test="sourceId != null">AND l.source_id = #{sourceId}</if>
            """;

    @Select("""
            <script>
            SELECT COALESCE(SUM(prompt_tokens), 0)
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            </script>
            """)
    long sumPromptTokens(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                         @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                         @Param("provider") String provider, @Param("modelName") String modelName,
                         @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT COALESCE(SUM(completion_tokens), 0)
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            </script>
            """)
    long sumCompletionTokens(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                             @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                             @Param("provider") String provider, @Param("modelName") String modelName,
                             @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT COALESCE(SUM(total_tokens), 0)
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            </script>
            """)
    long sumTotalTokens(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                        @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                        @Param("provider") String provider, @Param("modelName") String modelName,
                        @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT COALESCE(SUM(cost_amount), 0)
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            </script>
            """)
    BigDecimal sumCostAmount(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                             @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                             @Param("provider") String provider, @Param("modelName") String modelName,
                             @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT COALESCE(SUM(charged_credits), 0)
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            </script>
            """)
    long sumChargedCredits(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                           @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                           @Param("provider") String provider, @Param("modelName") String modelName,
                           @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            </script>
            """)
    long countUsage(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                    @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                    @Param("provider") String provider, @Param("modelName") String modelName,
                    @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT provider, model_name, COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(cost_amount), 0) AS cost_amount,
                   COALESCE(SUM(charged_credits), 0) AS charged_credits
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            GROUP BY provider, model_name
            ORDER BY cost_amount DESC, total_tokens DESC
            LIMIT 10
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "provider", javaType = String.class),
            @Arg(column = "model_name", javaType = String.class),
            @Arg(column = "total_tokens", javaType = long.class),
            @Arg(column = "cost_amount", javaType = BigDecimal.class),
            @Arg(column = "charged_credits", javaType = long.class)
    })
    List<ModelCostPoint> modelCosts(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                                    @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                                    @Param("provider") String provider, @Param("modelName") String modelName,
                                    @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT user_id, COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(cost_amount), 0) AS cost_amount,
                   COALESCE(SUM(charged_credits), 0) AS charged_credits,
                   COUNT(*) AS usage_count
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            GROUP BY user_id
            ORDER BY charged_credits DESC, cost_amount DESC
            LIMIT 10
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "user_id", javaType = Long.class),
            @Arg(column = "total_tokens", javaType = long.class),
            @Arg(column = "cost_amount", javaType = BigDecimal.class),
            @Arg(column = "charged_credits", javaType = long.class),
            @Arg(column = "usage_count", javaType = long.class)
    })
    List<UserCostPoint> userCosts(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                                  @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                                  @Param("provider") String provider, @Param("modelName") String modelName,
                                  @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT COALESCE(tool.output_modality, l.source_type, 'UNKNOWN') AS modality,
                   COALESCE(SUM(l.total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(l.cost_amount), 0) AS cost_amount,
                   COALESCE(SUM(l.charged_credits), 0) AS charged_credits,
                   COUNT(*) AS usage_count
            FROM billing_usage_logs l
            LEFT JOIN ai_tasks task ON l.source_type = 'TASK' AND l.source_id = task.id
            LEFT JOIN ai_tools tool ON task.tool_id = tool.id
            WHERE 1 = 1
            """ + ALIASED_FILTER + """
            GROUP BY COALESCE(tool.output_modality, l.source_type, 'UNKNOWN')
            ORDER BY charged_credits DESC, cost_amount DESC
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "modality", javaType = String.class),
            @Arg(column = "total_tokens", javaType = long.class),
            @Arg(column = "cost_amount", javaType = BigDecimal.class),
            @Arg(column = "charged_credits", javaType = long.class),
            @Arg(column = "usage_count", javaType = long.class)
    })
    List<ModalityCostPoint> modalityCosts(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                                          @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                                          @Param("provider") String provider, @Param("modelName") String modelName,
                                          @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT CAST(created_at AS DATE) AS usage_date,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(cost_amount), 0) AS cost_amount,
                   COALESCE(SUM(charged_credits), 0) AS charged_credits,
                   COUNT(*) AS usage_count
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            GROUP BY CAST(created_at AS DATE)
            ORDER BY usage_date DESC
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "usage_date", javaType = LocalDate.class),
            @Arg(column = "total_tokens", javaType = long.class),
            @Arg(column = "cost_amount", javaType = BigDecimal.class),
            @Arg(column = "charged_credits", javaType = long.class),
            @Arg(column = "usage_count", javaType = long.class)
    })
    List<DailyCostPoint> dailyCosts(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                                    @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                                    @Param("provider") String provider, @Param("modelName") String modelName,
                                    @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT l.id, l.source_type, l.source_id, task.task_no, tool.input_modality, tool.output_modality,
                   l.user_id, l.model_config_id, l.provider, l.model_name,
                   l.prompt_tokens, l.completion_tokens, l.total_tokens, l.input_token_price_per_1k,
                   l.output_token_price_per_1k, l.input_token_price_per_1m, l.output_token_price_per_1m,
                   l.billing_unit, l.billable_units, l.unit_price, l.cost_amount, l.charged_credits, l.created_at
            FROM billing_usage_logs l
            LEFT JOIN ai_tasks task ON l.source_type = 'TASK' AND l.source_id = task.id
            LEFT JOIN ai_tools tool ON task.tool_id = tool.id
            WHERE 1 = 1
            """ + ALIASED_FILTER + """
            ORDER BY l.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "source_type", javaType = String.class),
            @Arg(column = "source_id", javaType = Long.class),
            @Arg(column = "task_no", javaType = String.class),
            @Arg(column = "input_modality", javaType = String.class),
            @Arg(column = "output_modality", javaType = String.class),
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
    List<BillingUsageLogResponse> findLogs(@Param("limit") int limit, @Param("offset") int offset,
                                           @Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                                           @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                                           @Param("provider") String provider, @Param("modelName") String modelName,
                                           @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM billing_usage_logs
            WHERE 1 = 1
            """ + FILTER + """
            </script>
            """)
    long countLogs(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                   @Param("userId") Long userId, @Param("modelConfigId") Long modelConfigId,
                   @Param("provider") String provider, @Param("modelName") String modelName,
                   @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);
}
