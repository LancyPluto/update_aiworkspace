package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.credit.entity.ReferralRegistrationReward;
import com.aiminilab.aitoolmarket.credit.entity.UserReferral;
import com.aiminilab.aitoolmarket.credit.mapper.ReferralRegistrationRewardMapper;
import com.aiminilab.aitoolmarket.credit.mapper.UserReferralMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.ReferralCodeService;
import com.aiminilab.aitoolmarket.credit.service.ReferralService;
import com.aiminilab.aitoolmarket.user.entity.User;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ReferralServiceImpl implements ReferralService {
    private static final String INVITEE_ROLE = "INVITEE";
    private static final String INVITER_ROLE = "INVITER";
    private static final int INVITEE_REGISTRATION_REWARD = 200;
    private static final int INVITER_REGISTRATION_REWARD = 100;

    private final ReferralCodeService referralCodeService;
    private final UserReferralMapper referralMapper;
    private final ReferralRegistrationRewardMapper registrationRewardMapper;
    private final CreditService creditService;

    public ReferralServiceImpl(ReferralCodeService referralCodeService,
                               UserReferralMapper referralMapper,
                               ReferralRegistrationRewardMapper registrationRewardMapper,
                               CreditService creditService) {
        this.referralCodeService = referralCodeService;
        this.referralMapper = referralMapper;
        this.registrationRewardMapper = registrationRewardMapper;
        this.creditService = creditService;
    }

    @Override
    @Transactional
    public void bindInviteCode(Long inviteeUserId, String inviteCode) {
        if (inviteeUserId == null) {
            return;
        }
        User inviter = referralCodeService.findInviter(inviteCode).orElse(null);
        Long inviterUserId = inviter == null ? null : inviter.getId();
        if (inviterUserId == null || inviterUserId.equals(inviteeUserId)) {
            return;
        }
        UserReferral referral = new UserReferral();
        referral.setInviterUserId(inviterUserId);
        referral.setInviteeUserId(inviteeUserId);
        referral.setInviteCode(inviter.getReferralCode());
        referral.setStatus("REGISTERED");
        LocalDateTime now = LocalDateTime.now();
        referral.setCreatedAt(now);
        referral.setUpdatedAt(now);
        try {
            referralMapper.insert(referral);
        } catch (DuplicateKeyException ignored) {
            // 一个用户只能绑定一个邀请来源，重复注册请求或重试不覆盖原关系。
            referral = referralMapper.findByInviteeUserId(inviteeUserId);
            if (referral == null || !inviterUserId.equals(referral.getInviterUserId())) {
                return;
            }
        }
        grantRegistrationReward(referral, referral.getInviteeUserId(), INVITEE_ROLE, INVITEE_REGISTRATION_REWARD,
                "注册填写邀请码奖励");
        grantRegistrationReward(referral, referral.getInviterUserId(), INVITER_ROLE, INVITER_REGISTRATION_REWARD,
                "邀请码被新用户填写奖励");
    }

    private void grantRegistrationReward(UserReferral referral, Long beneficiaryUserId, String beneficiaryRole,
                                         int rewardCredits, String reason) {
        if (registrationRewardMapper.findByReferralAndRole(referral.getId(), beneficiaryRole) == null) {
            ReferralRegistrationReward reward = new ReferralRegistrationReward();
            reward.setReferralId(referral.getId());
            reward.setBeneficiaryUserId(beneficiaryUserId);
            reward.setBeneficiaryRole(beneficiaryRole);
            reward.setRewardCredits(rewardCredits);
            reward.setStatus("CREDITED");
            LocalDateTime now = LocalDateTime.now();
            reward.setCreatedAt(now);
            reward.setUpdatedAt(now);
            try {
                registrationRewardMapper.insert(reward);
            } catch (DuplicateKeyException ignored) {
                // 并发重试已创建同一受益方记录，继续通过算力日志幂等键确认入账。
            }
        }
        creditService.referralRegistrationBonusAdd(
                beneficiaryUserId,
                referral.getId(),
                beneficiaryRole,
                rewardCredits,
                reason
        );
    }

}
