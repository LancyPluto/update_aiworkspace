package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

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

    @Select("""
            SELECT *
            FROM credit_recharge_orders
            WHERE order_no = #{orderNo}
            LIMIT 1
            """)
    CreditRechargeOrder findByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
            SELECT *
            FROM credit_recharge_orders
            WHERE status = 'PAID'
              AND credited_at IS NULL
              AND updated_at <= #{updatedBefore}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<CreditRechargeOrder> findPaidNotCredited(@Param("updatedBefore") LocalDateTime updatedBefore,
                                                 @Param("limit") int limit);

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

    @Update("""
            UPDATE credit_recharge_orders
            SET external_trade_no = #{externalTradeNo},
                updated_at = #{eventAt}
            WHERE id = #{orderId}
              AND (external_trade_no IS NULL OR external_trade_no = #{externalTradeNo})
            """)
    int bindExternalTradeNo(@Param("orderId") Long orderId,
                            @Param("externalTradeNo") String externalTradeNo,
                            @Param("eventAt") LocalDateTime eventAt);

    @Update("""
            UPDATE credit_recharge_orders
            SET pay_url = #{payUrl},
                qr_code_url = NULL,
                status_reason = #{reason},
                updated_at = #{eventAt}
            WHERE id = #{orderId}
              AND status = 'WAITING_PAYMENT'
            """)
    int bindPayUrl(@Param("orderId") Long orderId,
                   @Param("payUrl") String payUrl,
                   @Param("reason") String reason,
                   @Param("eventAt") LocalDateTime eventAt);

    @Update("""
            UPDATE credit_recharge_orders
            SET status_reason = #{reason},
                updated_at = #{eventAt}
            WHERE id = #{orderId}
              AND status = #{status}
            """)
    int touchStatusReason(@Param("orderId") Long orderId,
                          @Param("status") String status,
                          @Param("reason") String reason,
                          @Param("eventAt") LocalDateTime eventAt);

    @Select("""
            SELECT crp.package_code
            FROM credit_recharge_orders cro
            INNER JOIN credit_recharge_packages crp ON cro.package_id = crp.id
            WHERE cro.user_id = #{userId}
              AND cro.status = 'CREDITED'
              AND crp.status = 'ACTIVE'
            ORDER BY cro.credited_at DESC, cro.id DESC
            LIMIT 1
            """)
    String findCurrentPackageCodeByUserId(@Param("userId") Long userId);
}
