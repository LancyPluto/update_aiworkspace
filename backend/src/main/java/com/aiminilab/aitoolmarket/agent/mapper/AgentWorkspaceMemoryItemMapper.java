package com.aiminilab.aitoolmarket.agent.mapper;

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
            INSERT INTO agent_workspace_memory_items(workspace_id, user_id, memory_type, title, content, source_run_id, status, created_at, updated_at)
            VALUES(#{item.workspaceId}, #{item.userId}, #{item.memoryType}, #{item.title}, #{item.content}, #{item.sourceRunId},
                   #{item.status}, #{item.createdAt}, #{item.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "item.id")
    void insertMemory(@Param("item") AgentWorkspaceMemoryItem item);

    @Select("""
            SELECT *
            FROM agent_workspace_memory_items
            WHERE workspace_id = #{workspaceId}
              AND status = 'ACTIVE'
            ORDER BY updated_at DESC, id DESC
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
}
