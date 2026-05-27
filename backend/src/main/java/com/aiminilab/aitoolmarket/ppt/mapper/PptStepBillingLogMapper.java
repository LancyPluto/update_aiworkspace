package com.aiminilab.aitoolmarket.ppt.mapper;

import com.aiminilab.aitoolmarket.ppt.entity.PptStepBillingLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

public interface PptStepBillingLogMapper extends BaseMapper<PptStepBillingLog> {

    @Select("""
            SELECT *
            FROM ppt_step_billing_logs
            WHERE binding_id = #{bindingId}
              AND step_code = #{stepCode}
              AND client_request_id = #{clientRequestId}
            LIMIT 1
            """)
    PptStepBillingLog findByIdempotentKey(@Param("bindingId") Long bindingId,
                                          @Param("stepCode") String stepCode,
                                          @Param("clientRequestId") String clientRequestId);

    default Optional<PptStepBillingLog> findIdempotent(Long bindingId, String stepCode, String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(findByIdempotentKey(bindingId, stepCode, clientRequestId.trim()));
    }

    @Insert("""
            INSERT INTO ppt_step_billing_logs (
              user_id, binding_id, step_code, credits_charged, credit_log_id, client_request_id, created_at
            ) VALUES (
              #{log.userId}, #{log.bindingId}, #{log.stepCode}, #{log.creditsCharged},
              #{log.creditLogId}, #{log.clientRequestId}, #{log.createdAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "log.id")
    int insertLog(@Param("log") PptStepBillingLog log);
}
