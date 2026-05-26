package com.aiminilab.aitoolmarket.market.mapper;

import com.aiminilab.aitoolmarket.market.entity.AiMarketTool;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

public interface AiMarketToolMapper extends BaseMapper<AiMarketTool> {

    @Select("""
            SELECT *
            FROM ai_market_tools
            WHERE COALESCE(is_deleted, 0) = 0
            ORDER BY sort_order ASC, id ASC
            """)
    List<AiMarketTool> findAllActive();

    @Select("""
            SELECT *
            FROM ai_market_tools
            WHERE enabled = 1 AND COALESCE(is_deleted, 0) = 0
            ORDER BY sort_order ASC, id ASC
            """)
    List<AiMarketTool> findAllEnabled();

    @Select("""
            SELECT *
            FROM ai_market_tools
            WHERE tool_id = #{toolId} AND COALESCE(is_deleted, 0) = 0
            LIMIT 1
            """)
    AiMarketTool findByToolId(@Param("toolId") String toolId);

    default Optional<AiMarketTool> findOptionalByToolId(String toolId) {
        return Optional.ofNullable(findByToolId(toolId));
    }

    @Select("""
            SELECT COUNT(*)
            FROM ai_market_tools
            WHERE tool_id = #{toolId} AND COALESCE(is_deleted, 0) = 0
            """)
    long countByToolId(@Param("toolId") String toolId);

    @Insert("""
            INSERT INTO ai_market_tools (
              tool_id, name, icon_url, description, enabled, sort_order,
              primary_color, welcome_message, capabilities_json, model_config_id,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{tool.toolId}, #{tool.name}, #{tool.iconUrl}, #{tool.description},
              #{tool.enabled}, #{tool.sortOrder}, #{tool.primaryColor}, #{tool.welcomeMessage},
              #{tool.capabilitiesJson}, #{tool.modelConfigId},
              #{tool.createdAt}, #{tool.updatedAt}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "tool.id")
    int insertTool(@Param("tool") AiMarketTool tool);

    @Update("""
            UPDATE ai_market_tools
            SET name = #{tool.name},
                icon_url = #{tool.iconUrl},
                description = #{tool.description},
                enabled = #{tool.enabled},
                sort_order = #{tool.sortOrder},
                primary_color = #{tool.primaryColor},
                welcome_message = #{tool.welcomeMessage},
                capabilities_json = #{tool.capabilitiesJson},
                model_config_id = #{tool.modelConfigId},
                updated_at = #{tool.updatedAt}
            WHERE tool_id = #{tool.toolId} AND COALESCE(is_deleted, 0) = 0
            """)
    int updateTool(@Param("tool") AiMarketTool tool);

    @Update("""
            UPDATE ai_market_tools
            SET is_deleted = 1, updated_at = CURRENT_TIMESTAMP
            WHERE tool_id = #{toolId} AND COALESCE(is_deleted, 0) = 0
            """)
    int softDelete(@Param("toolId") String toolId);
}
