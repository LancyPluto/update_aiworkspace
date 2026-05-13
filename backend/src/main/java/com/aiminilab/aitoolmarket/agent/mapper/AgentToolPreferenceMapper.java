package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentToolPreference;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentToolPreferenceMapper extends BaseMapper<AgentToolPreference> {

    @Select("""
            SELECT *
            FROM agent_tool_preferences
            WHERE user_id = #{userId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<AgentToolPreference> findByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM agent_tool_preferences
            WHERE user_id = #{userId} AND tool_code = #{toolCode}
            LIMIT 1
            """)
    AgentToolPreference findByUserIdAndToolCode(@Param("userId") Long userId, @Param("toolCode") String toolCode);

    @Insert("""
            INSERT INTO agent_tool_preferences(user_id, tool_code, auto_call_enabled, created_at, updated_at)
            VALUES(#{preference.userId}, #{preference.toolCode}, #{preference.autoCallEnabled}, #{preference.createdAt}, #{preference.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "preference.id")
    void insertPreference(@Param("preference") AgentToolPreference preference);

    @Update("""
            UPDATE agent_tool_preferences
            SET auto_call_enabled = #{autoCallEnabled}, updated_at = #{updatedAt}
            WHERE user_id = #{userId} AND tool_code = #{toolCode}
            """)
    void updatePreference(@Param("userId") Long userId,
                          @Param("toolCode") String toolCode,
                          @Param("autoCallEnabled") Boolean autoCallEnabled,
                          @Param("updatedAt") LocalDateTime updatedAt);
}
