package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    @Insert("""
            INSERT INTO agent_messages(session_id, user_id, role, content_text, content_json, run_id, status, superseded_at, created_at)
            VALUES(#{message.sessionId}, #{message.userId}, #{message.role}, #{message.contentText},
                   #{message.contentJson}, #{message.runId}, #{message.status}, #{message.supersededAt}, #{message.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "message.id")
    void insertMessage(@Param("message") AgentMessage message);

    @Select("""
            SELECT m.*
            FROM agent_messages m
            JOIN agent_sessions s ON s.id = m.session_id
            WHERE m.session_id = #{sessionId} AND s.user_id = #{userId}
              AND m.status = 'ACTIVE'
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
              AND m.status = 'ACTIVE'
            """)
    long countBySession(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Select("""
            SELECT *
            FROM agent_messages
            WHERE session_id = #{sessionId}
              AND status = 'ACTIVE'
              AND (#{beforeMessageId} IS NULL OR id < #{beforeMessageId})
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentMessage> findActiveHistoryBefore(@Param("sessionId") Long sessionId,
                                               @Param("beforeMessageId") Long beforeMessageId,
                                               @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_messages
            WHERE run_id = #{runId} AND role = 'USER'
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentMessage findUserMessageByRunId(@Param("runId") Long runId);

    @Select("""
            SELECT m.*
            FROM agent_messages m
            JOIN agent_sessions s ON s.id = m.session_id
            WHERE m.id = #{messageId}
              AND m.session_id = #{sessionId}
              AND s.user_id = #{userId}
            LIMIT 1
            """)
    AgentMessage selectByIdSessionAndUser(@Param("messageId") Long messageId,
                                          @Param("sessionId") Long sessionId,
                                          @Param("userId") Long userId);

    default Optional<AgentMessage> findByIdSessionAndUser(Long messageId, Long sessionId, Long userId) {
        return Optional.ofNullable(selectByIdSessionAndUser(messageId, sessionId, userId));
    }

    @Update("""
            UPDATE agent_messages
            SET status = 'SUPERSEDED', superseded_at = #{now}
            WHERE session_id = #{sessionId}
              AND id > #{anchorUserMessageId}
              AND status = 'ACTIVE'
            """)
    int supersedeMessagesAfter(@Param("sessionId") Long sessionId,
                               @Param("anchorUserMessageId") Long anchorUserMessageId,
                               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_messages
            SET status = 'SUPERSEDED', superseded_at = #{now}
            WHERE run_id = #{runId}
              AND role = 'ASSISTANT'
              AND status = 'ACTIVE'
            """)
    int supersedeActiveAssistantsByRunId(@Param("runId") Long runId, @Param("now") LocalDateTime now);
}
