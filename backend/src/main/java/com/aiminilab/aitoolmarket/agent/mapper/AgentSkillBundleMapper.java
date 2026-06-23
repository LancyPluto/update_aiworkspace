package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentSkillBundle;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentSkillBundleMapper extends BaseMapper<AgentSkillBundle> {

    @Select("""
            SELECT *
            FROM agent_skill_bundles
            WHERE skill_code = #{skillCode}
            ORDER BY version DESC, id DESC
            LIMIT 1
            """)
    AgentSkillBundle selectLatestBySkillCode(@Param("skillCode") String skillCode);

    @Select("""
            SELECT b.*
            FROM agent_skill_bundles b
            JOIN (
                SELECT skill_code, MAX(version) AS max_version
                FROM agent_skill_bundles
                GROUP BY skill_code
            ) latest ON latest.skill_code = b.skill_code AND latest.max_version = b.version
            ORDER BY b.skill_code ASC
            """)
    List<AgentSkillBundle> selectLatestAll();

    @Select("""
            SELECT *
            FROM agent_skill_bundles
            WHERE skill_code = #{skillCode} AND status = 'PUBLISHED'
            ORDER BY version DESC, id DESC
            LIMIT 1
            """)
    AgentSkillBundle selectPublishedBySkillCode(@Param("skillCode") String skillCode);

    @Select("""
            SELECT *
            FROM agent_skill_bundles
            WHERE status = 'PUBLISHED'
            ORDER BY skill_code ASC, version DESC
            """)
    List<AgentSkillBundle> selectPublished();
}
