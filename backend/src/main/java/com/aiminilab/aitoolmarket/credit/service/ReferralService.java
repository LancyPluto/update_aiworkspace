package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;

public interface ReferralService {
    void bindInviteCode(Long inviteeUserId, String inviteCode);

    void rewardRechargeIfNeeded(CreditRechargeOrder order);
}
