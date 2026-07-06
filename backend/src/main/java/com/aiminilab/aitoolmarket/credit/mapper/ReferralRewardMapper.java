package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.ReferralReward;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReferralRewardMapper extends BaseMapper<ReferralReward> {

    @Select("""
            SELECT *
            FROM referral_rewards
            WHERE recharge_order_id = #{rechargeOrderId}
            LIMIT 1
            """)
    ReferralReward findByRechargeOrderId(@Param("rechargeOrderId") Long rechargeOrderId);
}
