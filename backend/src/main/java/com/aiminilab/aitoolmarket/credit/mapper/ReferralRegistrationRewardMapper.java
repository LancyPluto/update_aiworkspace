package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.ReferralRegistrationReward;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReferralRegistrationRewardMapper extends BaseMapper<ReferralRegistrationReward> {

    @Select("""
            SELECT *
            FROM referral_registration_rewards
            WHERE referral_id = #{referralId} AND beneficiary_role = #{beneficiaryRole}
            LIMIT 1
            """)
    ReferralRegistrationReward findByReferralAndRole(@Param("referralId") Long referralId,
                                                     @Param("beneficiaryRole") String beneficiaryRole);
}
