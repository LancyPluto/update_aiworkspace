package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.UserReferral;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserReferralMapper extends BaseMapper<UserReferral> {

    @Select("""
            SELECT *
            FROM user_referrals
            WHERE invitee_user_id = #{inviteeUserId}
            LIMIT 1
            """)
    UserReferral findByInviteeUserId(@Param("inviteeUserId") Long inviteeUserId);
}
