package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface CreditRechargeOrderMapper extends BaseMapper<CreditRechargeOrder> {

    @Select("""
            SELECT *
            FROM credit_recharge_orders
            WHERE user_id = #{userId}
              AND idempotency_key = #{idempotencyKey}
            ORDER BY id DESC
            LIMIT 1
            """)
    CreditRechargeOrder findByUserAndIdempotencyKey(@Param("userId") Long userId,
                                                    @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT *
            FROM credit_recharge_orders
            WHERE id = #{orderId}
              AND user_id = #{userId}
            LIMIT 1
            """)
    CreditRechargeOrder findByIdAndUserId(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Update("""
            UPDATE credit_recharge_orders
            SET status = #{toStatus},
                status_reason = #{reason},
                paid_at = CASE WHEN #{toStatus} = 'PAID' AND paid_at IS NULL THEN #{eventAt} ELSE paid_at END,
                credited_at = CASE WHEN #{toStatus} = 'CREDITED' AND credited_at IS NULL THEN #{eventAt} ELSE credited_at END,
                closed_at = CASE WHEN #{toStatus} IN ('CLOSED', 'FAILED') AND closed_at IS NULL THEN #{eventAt} ELSE closed_at END,
                updated_at = #{eventAt}
            WHERE id = #{orderId}
              AND status = #{fromStatus}
            """)
    int transit(@Param("orderId") Long orderId,
                @Param("fromStatus") String fromStatus,
                @Param("toStatus") String toStatus,
                @Param("reason") String reason,
                @Param("eventAt") LocalDateTime eventAt);
}
