package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.AgentRunAuditReview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

public interface AgentRunAuditReviewMapper extends BaseMapper<AgentRunAuditReview> {
    @Select("SELECT * FROM agent_run_audit_reviews WHERE run_id = #{runId} LIMIT 1")
    AgentRunAuditReview findByRunId(@Param("runId") Long runId);

    @Insert("""
            INSERT INTO agent_run_audit_reviews(run_id,expected_tool_code,final_category,review_note,reviewed_by,created_at,updated_at)
            VALUES(#{item.runId},#{item.expectedToolCode},#{item.finalCategory},#{item.reviewNote},#{item.reviewedBy},#{item.createdAt},#{item.updatedAt})
            ON DUPLICATE KEY UPDATE expected_tool_code=VALUES(expected_tool_code), final_category=VALUES(final_category),
              review_note=VALUES(review_note), reviewed_by=VALUES(reviewed_by), updated_at=VALUES(updated_at)
            """)
    int upsert(@Param("item") AgentRunAuditReview item);
}
