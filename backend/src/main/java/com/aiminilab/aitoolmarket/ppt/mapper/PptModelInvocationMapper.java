package com.aiminilab.aitoolmarket.ppt.mapper;

import com.aiminilab.aitoolmarket.ppt.entity.PptModelInvocation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PptModelInvocationMapper extends BaseMapper<PptModelInvocation> {
    @Select("""
            SELECT * FROM ppt_model_invocations
            WHERE ppt_job_id = #{jobId} AND idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    PptModelInvocation findIdempotent(@Param("jobId") Long jobId,
                                      @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM ppt_model_invocations
            WHERE id = #{id} AND project_id = #{projectId} AND ppt_job_id = #{jobId}
            LIMIT 1
            """)
    PptModelInvocation findScoped(@Param("id") Long id,
                                  @Param("projectId") Long projectId,
                                  @Param("jobId") Long jobId);
}
