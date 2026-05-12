package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentModelConfigMapper extends BaseMapper<AgentModelConfig> {

    @Select("""
            SELECT *
            FROM agent_model_configs
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentModelConfig findLatest();

    @Insert("""
            INSERT INTO agent_model_configs(provider, model_name, base_url, api_key, minimax_group_id,
                                            timeout_seconds, enabled, created_at, updated_at)
            VALUES(#{config.provider}, #{config.modelName}, #{config.baseUrl}, #{config.apiKey},
                   #{config.minimaxGroupId}, #{config.timeoutSeconds}, #{config.enabled},
                   #{config.createdAt}, #{config.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "config.id")
    void insertConfig(@Param("config") AgentModelConfig config);

    @Update("""
            UPDATE agent_model_configs
            SET provider = #{config.provider},
                model_name = #{config.modelName},
                base_url = #{config.baseUrl},
                api_key = #{config.apiKey},
                minimax_group_id = #{config.minimaxGroupId},
                timeout_seconds = #{config.timeoutSeconds},
                enabled = #{config.enabled},
                updated_at = #{config.updatedAt}
            WHERE id = #{config.id}
            """)
    void updateConfig(@Param("config") AgentModelConfig config);
}
