package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentModelConfigMapper extends BaseMapper<AgentModelConfig> {

    @Select("""
            SELECT *
            FROM agent_model_configs
            WHERE COALESCE(is_deleted, 0) = 0
            ORDER BY COALESCE(is_default, 0) DESC, id DESC
            LIMIT 1
            """)
    AgentModelConfig findLatest();

    @Select("""
            SELECT *
            FROM agent_model_configs
            WHERE id = #{id}
              AND COALESCE(is_deleted, 0) = 0
            """)
    AgentModelConfig findActiveById(@Param("id") Long id);

    @Select("""
            SELECT *
            FROM agent_model_configs
            WHERE config_code = #{configCode}
              AND COALESCE(is_deleted, 0) = 0
            LIMIT 1
            """)
    AgentModelConfig findActiveByConfigCode(@Param("configCode") String configCode);

    @Select("""
            SELECT COUNT(1)
            FROM agent_model_configs
            WHERE config_code = #{configCode}
              AND COALESCE(is_deleted, 0) = 0
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countActiveByConfigCode(@Param("configCode") String configCode, @Param("excludeId") Long excludeId);

    @Update("""
            UPDATE agent_model_configs
            SET config_code = CONCAT(SUBSTRING(config_code, 1, 40), '__deleted_', id),
                updated_at = NOW()
            WHERE config_code = #{configCode}
              AND COALESCE(is_deleted, 0) = 1
            """)
    void archiveDeletedConfigCode(@Param("configCode") String configCode);

    @Select("""
            SELECT *
            FROM agent_model_configs
            WHERE COALESCE(is_deleted, 0) = 0
            ORDER BY COALESCE(is_default, 0) DESC, id DESC
            """)
    List<AgentModelConfig> findAllActive();

    @Select("""
            SELECT m.*
            FROM ai_tools t
            JOIN agent_model_configs m ON m.id = COALESCE(
                t.model_config_id,
                (
                    SELECT d.id
                    FROM agent_model_configs d
                    WHERE COALESCE(d.is_deleted, 0) = 0 AND d.enabled = 1
                    ORDER BY COALESCE(d.is_default, 0) DESC, d.id DESC
                    LIMIT 1
                )
            )
            WHERE t.id = #{toolId}
              AND t.is_deleted = 0
              AND COALESCE(m.is_deleted, 0) = 0
              AND m.enabled = 1
            LIMIT 1
            """)
    AgentModelConfig findForToolExecution(@Param("toolId") Long toolId);

    @Insert("""
            INSERT INTO agent_model_configs(display_name, config_code, provider, model_name, base_url, api_key,
                                            extra_auth_json, minimax_group_id, console_url, balance_url, docs_url,
                                            timeout_seconds, input_token_price_per_1k, output_token_price_per_1k,
                                            input_token_price_per_1m, output_token_price_per_1m,
                                            billing_unit, unit_price, capabilities, enabled, is_default, created_at, updated_at)
            VALUES(#{config.displayName}, #{config.configCode}, #{config.provider}, #{config.modelName},
                   #{config.baseUrl}, #{config.apiKey}, #{config.extraAuthJson}, #{config.minimaxGroupId},
                   #{config.consoleUrl}, #{config.balanceUrl}, #{config.docsUrl}, #{config.timeoutSeconds},
                   #{config.inputTokenPricePer1k}, #{config.outputTokenPricePer1k},
                   #{config.inputTokenPricePer1m}, #{config.outputTokenPricePer1m},
                   #{config.billingUnit}, #{config.unitPrice}, #{config.capabilities},
                   #{config.enabled}, #{config.default}, #{config.createdAt}, #{config.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "config.id")
    void insertConfig(@Param("config") AgentModelConfig config);

    @Update("""
            UPDATE agent_model_configs
            SET display_name = #{config.displayName},
                config_code = #{config.configCode},
                provider = #{config.provider},
                model_name = #{config.modelName},
                base_url = #{config.baseUrl},
                api_key = #{config.apiKey},
                extra_auth_json = #{config.extraAuthJson},
                minimax_group_id = #{config.minimaxGroupId},
                console_url = #{config.consoleUrl},
                balance_url = #{config.balanceUrl},
                docs_url = #{config.docsUrl},
                timeout_seconds = #{config.timeoutSeconds},
                input_token_price_per_1k = #{config.inputTokenPricePer1k},
                output_token_price_per_1k = #{config.outputTokenPricePer1k},
                input_token_price_per_1m = #{config.inputTokenPricePer1m},
                output_token_price_per_1m = #{config.outputTokenPricePer1m},
                billing_unit = #{config.billingUnit},
                unit_price = #{config.unitPrice},
                capabilities = #{config.capabilities},
                enabled = #{config.enabled},
                is_default = #{config.default},
                updated_at = #{config.updatedAt}
            WHERE id = #{config.id}
            """)
    void updateConfig(@Param("config") AgentModelConfig config);

    @Update("""
            UPDATE agent_model_configs
            SET is_default = 0,
                updated_at = NOW()
            WHERE id <> #{id}
            """)
    void clearDefaultExcept(@Param("id") Long id);

    @Update("""
            UPDATE agent_model_configs
            SET is_default = CASE WHEN id = #{id} THEN 1 ELSE 0 END,
                updated_at = NOW()
            WHERE COALESCE(is_deleted, 0) = 0
            """)
    void setDefault(@Param("id") Long id);

    @Update("""
            UPDATE agent_model_configs
            SET config_code = CASE
                    WHEN config_code IS NULL THEN NULL
                    ELSE CONCAT(SUBSTRING(config_code, 1, 40), '__deleted_', id)
                END,
                is_deleted = 1,
                is_default = 0,
                updated_at = NOW()
            WHERE id = #{id}
            """)
    void softDelete(@Param("id") Long id);
}
