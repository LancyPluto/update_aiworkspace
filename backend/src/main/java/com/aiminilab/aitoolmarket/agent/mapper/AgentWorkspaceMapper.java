package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspace;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentWorkspaceMapper extends BaseMapper<AgentWorkspace> {

    @Insert("""
            INSERT INTO agent_workspaces(owner_user_id, name, workspace_type, status, created_at, updated_at)
            VALUES(#{workspace.ownerUserId}, #{workspace.name}, #{workspace.workspaceType}, #{workspace.status},
                   #{workspace.createdAt}, #{workspace.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "workspace.id")
    void insertWorkspace(@Param("workspace") AgentWorkspace workspace);

    @Select("""
            SELECT *
            FROM agent_workspaces
            WHERE owner_user_id = #{userId} AND workspace_type = 'PERSONAL' AND status <> 'DELETED'
            ORDER BY id ASC
            LIMIT 1
            """)
    AgentWorkspace findPersonalByOwnerUserId(@Param("userId") Long userId);

    @Select("""
            SELECT w.id,
                   w.name,
                   w.workspace_type AS workspaceType,
                   m.role,
                   w.status,
                   w.created_at AS createdAt,
                   w.updated_at AS updatedAt
            FROM agent_workspaces w
            JOIN agent_workspace_members m ON m.workspace_id = w.id
            WHERE m.user_id = #{userId}
              AND m.status = 'ACTIVE'
              AND w.status <> 'DELETED'
            ORDER BY w.updated_at DESC, w.id DESC
            """)
    List<WorkspaceRow> findActiveByMemberUserId(@Param("userId") Long userId);

    class WorkspaceRow {
        private Long id;
        private String name;
        private String workspaceType;
        private String role;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getWorkspaceType() { return workspaceType; }
        public void setWorkspaceType(String workspaceType) { this.workspaceType = workspaceType; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}
