package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
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
            SELECT *
            FROM agent_model_configs
            WHERE vendor_account_id = #{vendorAccountId}
              AND model_name = #{modelName}
              AND COALESCE(is_deleted, 0) = 0
            LIMIT 1
            """)
    AgentModelConfig findActiveByVendorAccountAndModelName(@Param("vendorAccountId") Long vendorAccountId,
                                                           @Param("modelName") String modelName);

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
            SELECT *
            FROM agent_model_configs
            WHERE vendor_account_id = #{vendorAccountId}
              AND COALESCE(is_deleted, 0) = 0
            ORDER BY COALESCE(enabled, 0) DESC,
                     COALESCE(agent_enabled, 0) DESC,
                     COALESCE(is_default, 0) DESC,
                     id DESC
            """)
    List<AgentModelConfig> findActiveByVendorAccountId(@Param("vendorAccountId") Long vendorAccountId);

    @Select("""
            SELECT model.*
            FROM agent_model_configs model
            JOIN model_vendor_accounts account ON account.id = model.vendor_account_id
            WHERE account.vendor_code = #{vendorCode}
              AND COALESCE(account.is_deleted, 0) = 0
              AND account.enabled = 1
              AND account.load_balance_enabled = 1
              AND COALESCE(model.is_deleted, 0) = 0
              AND model.enabled = 1
              AND LOWER(model.provider) = LOWER(#{provider})
              AND model.model_name = #{modelName}
            ORDER BY model.id ASC
            """)
    List<AgentModelConfig> findRoutingCandidates(@Param("vendorCode") String vendorCode,
                                                  @Param("provider") String provider,
                                                  @Param("modelName") String modelName);

    @Select("""
            SELECT *
            FROM agent_model_configs
            WHERE COALESCE(is_deleted, 0) = 0
              AND enabled = 1
              AND COALESCE(agent_enabled, 0) = 1
            ORDER BY COALESCE(is_default, 0) DESC, id DESC
            """)
    List<AgentModelConfig> findAgentEnabled();

    @Select("""
            SELECT *
            FROM agent_model_configs
            WHERE id = #{id}
              AND COALESCE(is_deleted, 0) = 0
              AND enabled = 1
              AND COALESCE(agent_enabled, 0) = 1
            LIMIT 1
            """)
    AgentModelConfig findAgentEnabledById(@Param("id") Long id);

    @Select("""
            SELECT *
            FROM agent_model_configs
            WHERE vendor_account_id = #{vendorAccountId}
              AND COALESCE(is_deleted, 0) = 0
              AND enabled = 1
              AND COALESCE(agent_enabled, 0) = 1
              AND UPPER(COALESCE(capabilities, '')) LIKE '%TEXT_GENERATION%'
            ORDER BY COALESCE(is_default, 0) DESC, id DESC
            LIMIT 1
            """)
    AgentModelConfig findFirstEnabledTextByVendorAccountId(@Param("vendorAccountId") Long vendorAccountId);

    @Select("""
            SELECT *
            FROM agent_model_configs
            WHERE vendor_account_id = #{vendorAccountId}
              AND COALESCE(is_deleted, 0) = 0
              AND enabled = 1
            ORDER BY COALESCE(agent_enabled, 0) DESC, COALESCE(is_default, 0) DESC, id DESC
            LIMIT 1
            """)
    AgentModelConfig findFirstEnabledByVendorAccountId(@Param("vendorAccountId") Long vendorAccountId);

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

    @Update("""
            UPDATE agent_model_configs
            SET vendor_account_id = #{vendorAccountId},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateVendorAccountId(@Param("id") Long id,
                               @Param("vendorAccountId") Long vendorAccountId,
                               @Param("updatedAt") LocalDateTime updatedAt);

    @Insert("""
            INSERT INTO agent_model_configs(vendor_account_id, display_name, config_code, provider, model_name, base_url, api_key,
                                            extra_auth_json, execution_task, execution_options_json,
                                            minimax_group_id, console_url, balance_url, docs_url,
                                            timeout_seconds, input_token_price_per_1k, output_token_price_per_1k,
                                            input_token_price_per_1m, output_token_price_per_1m,
                                            billing_unit, unit_price, capabilities, enabled, agent_enabled,
                                            is_default, created_at, updated_at)
            VALUES(#{config.vendorAccountId}, #{config.displayName}, #{config.configCode}, #{config.provider}, #{config.modelName},
                   #{config.baseUrl}, #{config.apiKey}, #{config.extraAuthJson}, #{config.executionTask},
                   #{config.executionOptionsJson}, #{config.minimaxGroupId},
                   #{config.consoleUrl}, #{config.balanceUrl}, #{config.docsUrl}, #{config.timeoutSeconds},
                   #{config.inputTokenPricePer1k}, #{config.outputTokenPricePer1k},
                   #{config.inputTokenPricePer1m}, #{config.outputTokenPricePer1m},
                   #{config.billingUnit}, #{config.unitPrice}, #{config.capabilities},
                   #{config.enabled}, #{config.agentEnabled}, #{config.default},
                   #{config.createdAt}, #{config.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "config.id")
    void insertConfig(@Param("config") AgentModelConfig config);

    @Update("""
            UPDATE agent_model_configs
            SET vendor_account_id = #{config.vendorAccountId},
                display_name = #{config.displayName},
                config_code = #{config.configCode},
                provider = #{config.provider},
                model_name = #{config.modelName},
                base_url = #{config.baseUrl},
                api_key = #{config.apiKey},
                extra_auth_json = #{config.extraAuthJson},
                execution_task = #{config.executionTask},
                execution_options_json = #{config.executionOptionsJson},
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
                agent_enabled = #{config.agentEnabled},
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

    @Update("""
            UPDATE agent_model_configs
            SET last_test_success = #{config.lastTestSuccess},
                last_test_message = #{config.lastTestMessage},
                last_test_at = #{config.lastTestAt},
                updated_at = #{config.updatedAt}
            WHERE id = #{config.id}
            """)
    void updateConnectivityTest(@Param("config") AgentModelConfig config);

    @Update("""
            UPDATE agent_model_configs
            SET enabled = 1,
                updated_at = NOW()
            WHERE vendor_account_id = #{vendorAccountId}
              AND COALESCE(is_deleted, 0) = 0
              AND COALESCE(enabled, 0) = 0
            """)
    int enableByVendorAccountId(@Param("vendorAccountId") Long vendorAccountId);

    @Update("""
            UPDATE agent_model_configs
            SET config_code = CASE
                    WHEN config_code IS NULL THEN NULL
                    ELSE CONCAT(SUBSTRING(config_code, 1, 40), '__deleted_', id)
                END,
                enabled = 0,
                agent_enabled = 0,
                is_default = 0,
                is_deleted = 1,
                updated_at = NOW()
            WHERE vendor_account_id IN (
                SELECT id
                FROM model_vendor_accounts
                WHERE vendor_code = #{vendorCode}
                  AND COALESCE(is_deleted, 0) = 0
            )
              AND COALESCE(is_deleted, 0) = 0
            """)
    int softDeleteByVendorCode(@Param("vendorCode") String vendorCode);
}
