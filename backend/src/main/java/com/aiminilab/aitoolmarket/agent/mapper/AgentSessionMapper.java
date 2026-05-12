package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentSessionMapper extends BaseMapper<AgentSession> {

    @Insert("""
            INSERT INTO agent_sessions(user_id, workspace_id, title, status, created_at, updated_at)
            VALUES(#{session.userId}, #{session.workspaceId}, #{session.title}, #{session.status}, #{session.createdAt}, #{session.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "session.id")
    void insertSession(@Param("session") AgentSession session);

    @Select("""
            SELECT *
            FROM agent_sessions
            WHERE id = #{sessionId} AND user_id = #{userId} AND status <> 'DELETED'
            LIMIT 1
            """)
    AgentSession selectByIdAndUserId(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    default Optional<AgentSession> findByIdAndUserId(Long sessionId, Long userId) {
        return Optional.ofNullable(selectByIdAndUserId(sessionId, userId));
    }

    @Select("""
            SELECT *
            FROM agent_sessions
            WHERE user_id = #{userId} AND status <> 'DELETED'
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AgentSession> findByUserId(@Param("userId") Long userId,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM agent_sessions
            WHERE user_id = #{userId} AND status <> 'DELETED'
            """)
    long countByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE agent_sessions
            SET updated_at = #{updatedAt}
            WHERE id = #{sessionId}
            """)
    void touch(@Param("sessionId") Long sessionId, @Param("updatedAt") LocalDateTime updatedAt);
}
