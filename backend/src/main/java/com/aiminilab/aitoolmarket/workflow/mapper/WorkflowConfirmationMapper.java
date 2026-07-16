package com.aiminilab.aitoolmarket.workflow.mapper;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowConfirmation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WorkflowConfirmationMapper extends BaseMapper<WorkflowConfirmation> {

    @Select("SELECT * FROM workflow_confirmations WHERE token_hash = #{tokenHash} LIMIT 1")
    WorkflowConfirmation selectByTokenHash(@Param("tokenHash") String tokenHash);

    @Select("SELECT * FROM workflow_confirmations WHERE token_hash = #{tokenHash} LIMIT 1 FOR UPDATE")
    WorkflowConfirmation selectByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Select("""
            SELECT * FROM workflow_confirmations
            WHERE run_id = #{runId} AND status = 'PENDING'
            ORDER BY id DESC LIMIT 1
            """)
    WorkflowConfirmation selectPendingByRunId(@Param("runId") Long runId);

    @Update("""
            UPDATE workflow_confirmations
            SET token_hash = #{tokenHash}
            WHERE id = #{confirmationId} AND token_hash = #{placeholderHash}
            """)
    int replaceTokenHash(@Param("confirmationId") Long confirmationId,
                         @Param("placeholderHash") String placeholderHash,
                         @Param("tokenHash") String tokenHash);

    @Update("""
            UPDATE workflow_confirmations
            SET status = 'CONSUMED', decision = #{decision}, feedback_json = #{feedbackJson},
                consumed_at = CURRENT_TIMESTAMP
            WHERE id = #{confirmationId} AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP
            """)
    int consumeIfPending(@Param("confirmationId") Long confirmationId,
                         @Param("decision") String decision,
                         @Param("feedbackJson") String feedbackJson);

    @Update("""
            UPDATE workflow_confirmations
            SET status = 'EXPIRED'
            WHERE id = #{confirmationId} AND status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP
            """)
    int expireIfPending(@Param("confirmationId") Long confirmationId);

    @Update("""
            UPDATE workflow_confirmations
            SET status = 'CANCELLED', decision = COALESCE(decision, 'CANCEL')
            WHERE run_id = #{runId} AND status = 'PENDING'
            """)
    int cancelPendingByRunId(@Param("runId") Long runId);
}
