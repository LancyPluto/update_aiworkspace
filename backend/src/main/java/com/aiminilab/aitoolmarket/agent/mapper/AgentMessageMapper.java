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
            INSERT INTO agent_messages(session_id, user_id, role, content_text, content_json, run_id, parent_message_id, status, superseded_at, created_at)
            VALUES(#{message.sessionId}, #{message.userId}, #{message.role}, #{message.contentText},
                   #{message.contentJson}, #{message.runId}, #{message.parentMessageId}, #{message.status}, #{message.supersededAt}, #{message.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "message.id")
    void insertMessage(@Param("message") AgentMessage message);

    @Select("""
            SELECT page.*
            FROM (
                SELECT m.*
                FROM agent_messages m
                JOIN agent_sessions s ON s.id = m.session_id
                WHERE m.session_id = #{sessionId} AND s.user_id = #{userId}
                  AND m.status = 'ACTIVE'
                ORDER BY m.id DESC
                LIMIT #{limit} OFFSET #{offset}
            ) page
            ORDER BY page.id ASC
            """)
    List<AgentMessage> findBySession(@Param("userId") Long userId,
                                     @Param("sessionId") Long sessionId,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            WITH RECURSIVE active_path(id, session_id, user_id, role, content_text, content_json, run_id, parent_message_id, status, superseded_at, edited_at, created_at, depth) AS (
                SELECT m.id, m.session_id, m.user_id, m.role, m.content_text, m.content_json, m.run_id,
                       m.parent_message_id, m.status, m.superseded_at, m.edited_at, m.created_at, 0
                FROM agent_messages m
                WHERE m.session_id = #{sessionId}
                  AND m.id = #{leafMessageId}
                  AND m.status = 'ACTIVE'
                UNION ALL
                SELECT parent.id, parent.session_id, parent.user_id, parent.role, parent.content_text, parent.content_json,
                       parent.run_id, parent.parent_message_id, parent.status, parent.superseded_at, parent.edited_at,
                       parent.created_at, active_path.depth + 1
                FROM agent_messages parent
                JOIN active_path ON active_path.parent_message_id = parent.id
                WHERE parent.session_id = #{sessionId}
                  AND parent.status = 'ACTIVE'
            )
            SELECT id, session_id, user_id, role, content_text, content_json, run_id, parent_message_id, status, superseded_at, edited_at, created_at
            FROM active_path
            ORDER BY depth DESC
            """)
    List<AgentMessage> findActivePathByLeaf(@Param("sessionId") Long sessionId,
                                            @Param("leafMessageId") Long leafMessageId);

    @Select("""
            SELECT *
            FROM agent_messages
            WHERE session_id = #{sessionId}
              AND status = 'ACTIVE'
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentMessage findLatestActiveBySession(@Param("sessionId") Long sessionId);

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
            WHERE session_id = #{sessionId}
              AND status = 'ACTIVE'
              AND role = #{role}
              AND (
                    (#{parentMessageId} IS NULL AND parent_message_id IS NULL)
                    OR parent_message_id = #{parentMessageId}
                  )
            ORDER BY id ASC
            """)
    List<AgentMessage> findActiveSiblings(@Param("sessionId") Long sessionId,
                                          @Param("parentMessageId") Long parentMessageId,
                                          @Param("role") String role);

    @Select("""
            WITH RECURSIVE subtree(id, session_id, user_id, role, content_text, content_json, run_id, parent_message_id, status, superseded_at, edited_at, created_at, depth) AS (
                SELECT m.id, m.session_id, m.user_id, m.role, m.content_text, m.content_json, m.run_id,
                       m.parent_message_id, m.status, m.superseded_at, m.edited_at, m.created_at, 0
                FROM agent_messages m
                WHERE m.session_id = #{sessionId}
                  AND m.id = #{rootMessageId}
                  AND m.status = 'ACTIVE'
                UNION ALL
                SELECT child.id, child.session_id, child.user_id, child.role, child.content_text, child.content_json,
                       child.run_id, child.parent_message_id, child.status, child.superseded_at, child.edited_at,
                       child.created_at, subtree.depth + 1
                FROM agent_messages child
                JOIN subtree ON child.parent_message_id = subtree.id
                WHERE child.session_id = #{sessionId}
                  AND child.status = 'ACTIVE'
            )
            SELECT id, session_id, user_id, role, content_text, content_json, run_id, parent_message_id, status, superseded_at, edited_at, created_at
            FROM subtree
            WHERE NOT EXISTS (
                SELECT 1
                FROM agent_messages child
                WHERE child.session_id = #{sessionId}
                  AND child.parent_message_id = subtree.id
                  AND child.status = 'ACTIVE'
            )
            ORDER BY depth DESC, id DESC
            LIMIT 1
            """)
    AgentMessage findLatestActiveLeafInSubtree(@Param("sessionId") Long sessionId,
                                               @Param("rootMessageId") Long rootMessageId);

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

    @Select("""
            SELECT *
            FROM agent_messages
            WHERE run_id = #{runId}
              AND role = 'ASSISTANT'
              AND status = 'ACTIVE'
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentMessage findActiveAssistantByRunId(@Param("runId") Long runId);

    @Update("""
            UPDATE agent_messages
            SET content_text = #{contentText}, edited_at = #{editedAt}
            WHERE id = #{messageId}
            """)
    int updateContentText(@Param("messageId") Long messageId,
                          @Param("contentText") String contentText,
                          @Param("editedAt") LocalDateTime editedAt);

    @Select("""
            SELECT m.*
            FROM agent_messages m
            WHERE m.session_id = #{sessionId}
              AND m.user_id = #{userId}
              AND m.status = 'ACTIVE'
              AND (#{query} IS NULL OR #{query} = '' OR LOWER(m.content_text) LIKE CONCAT('%', LOWER(#{query}), '%'))
            ORDER BY m.id DESC
            LIMIT #{limit}
            """)
    List<AgentMessage> searchActiveBySession(@Param("userId") Long userId,
                                             @Param("sessionId") Long sessionId,
                                             @Param("query") String query,
                                             @Param("limit") int limit);

    @Select("""
            SELECT COUNT(1)
            FROM agent_messages
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND (
                content_text LIKE CONCAT('%', #{relativeKey})
                OR content_json LIKE CONCAT('%', #{relativeKey})
              )
            """)
    long countActiveByUserContainingRelativeKey(@Param("userId") Long userId,
                                                @Param("relativeKey") String relativeKey);
}
