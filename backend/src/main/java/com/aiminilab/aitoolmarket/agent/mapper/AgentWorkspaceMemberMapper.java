package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentWorkspaceMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentWorkspaceMemberMapper extends BaseMapper<AgentWorkspaceMember> {

    @Insert("""
            INSERT INTO agent_workspace_members(workspace_id, user_id, role, status, created_at, updated_at)
            VALUES(#{member.workspaceId}, #{member.userId}, #{member.role}, #{member.status},
                   #{member.createdAt}, #{member.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "member.id")
    void insertMember(@Param("member") AgentWorkspaceMember member);

    @Select("""
            SELECT *
            FROM agent_workspace_members
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId} AND status = 'ACTIVE'
            LIMIT 1
            """)
    AgentWorkspaceMember findActiveMember(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);
}
