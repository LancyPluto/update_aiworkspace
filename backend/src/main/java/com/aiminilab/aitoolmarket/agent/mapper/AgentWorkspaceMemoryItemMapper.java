package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.dto.InternalWorkspaceMemoryItemResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspaceMemoryItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentWorkspaceMemoryItemMapper extends BaseMapper<AgentWorkspaceMemoryItem> {

    @Insert("""
            INSERT INTO agent_workspace_memory_items(workspace_id, user_id, memory_type, title, content, source_run_id,
                                                     source_message_id, source_tool_call_id, importance, confidence, pinned,
                                                     tags_json, metadata_json, expires_at, status, created_at, updated_at)
            VALUES(#{item.workspaceId}, #{item.userId}, #{item.memoryType}, #{item.title}, #{item.content}, #{item.sourceRunId},
                   #{item.sourceMessageId}, #{item.sourceToolCallId}, #{item.importance}, #{item.confidence}, #{item.pinned},
                   #{item.tagsJson}, #{item.metadataJson}, #{item.expiresAt}, #{item.status}, #{item.createdAt}, #{item.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "item.id")
    void insertMemory(@Param("item") AgentWorkspaceMemoryItem item);

    @Select("""
            SELECT *
            FROM agent_workspace_memory_items
            WHERE workspace_id = #{workspaceId}
              AND status = 'ACTIVE'
            ORDER BY pinned DESC, importance DESC, updated_at DESC, id DESC
            """)
    List<AgentWorkspaceMemoryItem> findActiveByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Select("""
            SELECT *
            FROM agent_workspace_memory_items
            WHERE id = #{memoryId}
              AND workspace_id = #{workspaceId}
              AND status = 'ACTIVE'
            LIMIT 1
            """)
    AgentWorkspaceMemoryItem findActiveById(@Param("workspaceId") Long workspaceId, @Param("memoryId") Long memoryId);

    @Update("""
            UPDATE agent_workspace_memory_items
            SET memory_type = #{item.memoryType},
                title = #{item.title},
                content = #{item.content},
                importance = #{item.importance},
                confidence = #{item.confidence},
                pinned = #{item.pinned},
                tags_json = #{item.tagsJson},
                metadata_json = #{item.metadataJson},
                expires_at = #{item.expiresAt},
                updated_at = #{item.updatedAt}
            WHERE id = #{item.id}
              AND workspace_id = #{item.workspaceId}
              AND status = 'ACTIVE'
            """)
    int updateMemory(@Param("item") AgentWorkspaceMemoryItem item);

    @Update("""
            UPDATE agent_workspace_memory_items
            SET status = 'DELETED',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{memoryId}
              AND workspace_id = #{workspaceId}
              AND status = 'ACTIVE'
            """)
    int softDelete(@Param("workspaceId") Long workspaceId, @Param("memoryId") Long memoryId);

    @Select("""
            SELECT id, workspace_id, source_run_id, title, content, memory_type, status,
                   MATCH(title, content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score,
                   updated_at
            FROM agent_workspace_memory_items
            WHERE workspace_id = #{workspaceId}
              AND status = 'ACTIVE'
              AND MATCH(title, content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            ORDER BY pinned DESC, score DESC, importance DESC, updated_at DESC
            LIMIT #{limit}
            """)
    List<InternalWorkspaceMemoryItemResponse> searchByFulltext(
            @Param("workspaceId") Long workspaceId,
            @Param("query") String query,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id, workspace_id, source_run_id, title, content, memory_type, status,
                   updated_at
            FROM agent_workspace_memory_items
            WHERE workspace_id = #{workspaceId} AND status = 'ACTIVE'
            ORDER BY pinned DESC, importance DESC, updated_at DESC
            LIMIT #{limit}
            """)
    List<InternalWorkspaceMemoryItemResponse> findLatestByWorkspace(
            @Param("workspaceId") Long workspaceId,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE agent_workspace_memory_items
            SET pinned = #{pinned},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{memoryId}
              AND workspace_id = #{workspaceId}
              AND status = 'ACTIVE'
            """)
    int updatePinned(@Param("workspaceId") Long workspaceId, @Param("memoryId") Long memoryId, @Param("pinned") boolean pinned);

    @Update("""
            UPDATE agent_workspace_memory_items
            SET last_accessed_at = CURRENT_TIMESTAMP,
                access_count = access_count + 1
            WHERE workspace_id = #{workspaceId}
              AND id IN (${ids})
              AND status = 'ACTIVE'
            """)
    int markAccessed(@Param("workspaceId") Long workspaceId, @Param("ids") String ids);
}
