package com.aiminilab.aitoolmarket.market.mapper;

import com.aiminilab.aitoolmarket.market.entity.AiMarketSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiMarketSessionMapper extends BaseMapper<AiMarketSession> {

    @Select("""
            SELECT *
            FROM ai_market_sessions
            WHERE user_id = #{userId} AND tool_id = #{toolId} AND COALESCE(is_deleted, 0) = 0
            ORDER BY updated_at DESC
            """)
    List<AiMarketSession> findByUserAndTool(@Param("userId") Long userId, @Param("toolId") String toolId);

    @Select("""
            SELECT *
            FROM ai_market_sessions
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND COALESCE(is_deleted, 0) = 0
            LIMIT 1
            """)
    AiMarketSession findBySessionIdAndUser(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    default Optional<AiMarketSession> findOptional(String sessionId, Long userId) {
        return Optional.ofNullable(findBySessionIdAndUser(sessionId, userId));
    }

    @Insert("""
            INSERT INTO ai_market_sessions (
              session_id, user_id, tool_id, title, created_at, updated_at, is_deleted
            ) VALUES (
              #{session.sessionId}, #{session.userId}, #{session.toolId}, #{session.title},
              #{session.createdAt}, #{session.updatedAt}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "session.id")
    int insertSession(@Param("session") AiMarketSession session);

    @Update("""
            UPDATE ai_market_sessions
            SET title = #{title}, updated_at = #{updatedAt}
            WHERE session_id = #{sessionId} AND COALESCE(is_deleted, 0) = 0
            """)
    int updateTitle(@Param("sessionId") String sessionId,
                    @Param("title") String title,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE ai_market_sessions
            SET updated_at = #{updatedAt}
            WHERE session_id = #{sessionId} AND COALESCE(is_deleted, 0) = 0
            """)
    int touch(@Param("sessionId") String sessionId, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE ai_market_sessions
            SET is_deleted = 1, updated_at = #{updatedAt}
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND COALESCE(is_deleted, 0) = 0
            """)
    int softDelete(@Param("sessionId") String sessionId,
                   @Param("userId") Long userId,
                   @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT COUNT(*)
            FROM ai_market_messages
            WHERE session_id = #{sessionId} AND role = 'user'
            """)
    long countUserMessages(@Param("sessionId") String sessionId);
}
