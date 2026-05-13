package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    @Insert("""
            INSERT INTO agent_messages(session_id, user_id, role, content_text, content_json, run_id, created_at)
            VALUES(#{message.sessionId}, #{message.userId}, #{message.role}, #{message.contentText},
                   #{message.contentJson}, #{message.runId}, #{message.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "message.id")
    void insertMessage(@Param("message") AgentMessage message);

    @Select("""
            SELECT m.*
            FROM agent_messages m
            JOIN agent_sessions s ON s.id = m.session_id
            WHERE m.session_id = #{sessionId} AND s.user_id = #{userId}
            ORDER BY m.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AgentMessage> findBySession(@Param("userId") Long userId,
                                     @Param("sessionId") Long sessionId,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM agent_messages m
            JOIN agent_sessions s ON s.id = m.session_id
            WHERE m.session_id = #{sessionId} AND s.user_id = #{userId}
            """)
    long countBySession(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Select("""
            SELECT *
            FROM agent_messages
            WHERE session_id = #{sessionId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentMessage> findLatestBySession(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_messages
            WHERE run_id = #{runId} AND role = 'USER'
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentMessage findUserMessageByRunId(@Param("runId") Long runId);
}
