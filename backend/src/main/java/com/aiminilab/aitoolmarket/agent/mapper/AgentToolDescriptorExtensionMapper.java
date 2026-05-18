package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentToolDescriptorExtension;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

public interface AgentToolDescriptorExtensionMapper extends BaseMapper<AgentToolDescriptorExtension> {

    @Select("""
            SELECT *
            FROM agent_tool_descriptor_extension
            WHERE tool_code = #{toolCode}
            LIMIT 1
            """)
    AgentToolDescriptorExtension selectByToolCode(@Param("toolCode") String toolCode);

    default Optional<AgentToolDescriptorExtension> findByToolCode(String toolCode) {
        return Optional.ofNullable(selectByToolCode(toolCode));
    }

    @Select("""
            SELECT *
            FROM agent_tool_descriptor_extension
            WHERE agent_enabled = 1 AND agent_recommendable = 1
            """)
    List<AgentToolDescriptorExtension> findRecommendable();

    @Select("""
            SELECT *
            FROM agent_tool_descriptor_extension
            WHERE agent_enabled = 1 AND agent_auto_callable = 1
            """)
    List<AgentToolDescriptorExtension> findAutoCallable();
}
