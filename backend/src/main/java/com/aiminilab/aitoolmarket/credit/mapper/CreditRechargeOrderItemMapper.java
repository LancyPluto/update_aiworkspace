package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrderItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CreditRechargeOrderItemMapper extends BaseMapper<CreditRechargeOrderItem> {

    @Select("""
            SELECT *
            FROM credit_recharge_order_items
            WHERE order_id = #{orderId}
            ORDER BY id ASC
            """)
    List<CreditRechargeOrderItem> findByOrderId(@Param("orderId") Long orderId);
}
