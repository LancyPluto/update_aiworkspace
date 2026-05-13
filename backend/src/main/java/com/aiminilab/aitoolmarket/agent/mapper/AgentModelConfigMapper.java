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
            ORDER BY id DESC
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
            WHERE COALESCE(is_deleted, 0) = 0
            ORDER BY COALESCE(is_default, 0) DESC, id DESC
            """)
    List<AgentModelConfig> findAllActive();

    @Insert("""
            INSERT INTO agent_model_configs(display_name, config_code, provider, model_name, base_url, api_key,
                                            minimax_group_id, timeout_seconds, enabled, is_default, created_at, updated_at)
            VALUES(#{config.displayName}, #{config.configCode}, #{config.provider}, #{config.modelName},
                   #{config.baseUrl}, #{config.apiKey}, #{config.minimaxGroupId}, #{config.timeoutSeconds},
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
                minimax_group_id = #{config.minimaxGroupId},
                timeout_seconds = #{config.timeoutSeconds},
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
            SET is_deleted = 1,
                is_default = 0,
                updated_at = NOW()
            WHERE id = #{id}
            """)
    void softDelete(@Param("id") Long id);
}
