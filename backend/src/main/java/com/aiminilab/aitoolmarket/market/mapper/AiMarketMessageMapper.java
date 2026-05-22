package com.aiminilab.aitoolmarket.market.mapper;

import com.aiminilab.aitoolmarket.market.entity.AiMarketMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiMarketMessageMapper extends BaseMapper<AiMarketMessage> {

    @Select("""
            SELECT *
            FROM ai_market_messages
            WHERE session_id = #{sessionId}
            ORDER BY created_at ASC
            """)
    List<AiMarketMessage> findBySessionId(@Param("sessionId") String sessionId);

    @Insert("""
            INSERT INTO ai_market_messages (
              message_id, session_id, role, content, params_json, created_at
            ) VALUES (
              #{message.messageId}, #{message.sessionId}, #{message.role},
              #{message.content}, #{message.paramsJson}, #{message.createdAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "message.id")
    int insertMessage(@Param("message") AiMarketMessage message);

    @Delete("DELETE FROM ai_market_messages WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
