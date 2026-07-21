package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.credit.entity.ReferralRegistrationReward;
import com.aiminilab.aitoolmarket.credit.entity.UserReferral;
import com.aiminilab.aitoolmarket.credit.mapper.ReferralRegistrationRewardMapper;
import com.aiminilab.aitoolmarket.credit.mapper.UserReferralMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.ReferralService;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ReferralServiceImpl implements ReferralService {
    private static final String INVITE_CODE_PREFIX = "WLCLOUD";
    private static final String INVITEE_ROLE = "INVITEE";
    private static final String INVITER_ROLE = "INVITER";
    private static final int INVITEE_REGISTRATION_REWARD = 200;
    private static final int INVITER_REGISTRATION_REWARD = 100;

    private final UserMapper userMapper;
    private final UserReferralMapper referralMapper;
    private final ReferralRegistrationRewardMapper registrationRewardMapper;
    private final CreditService creditService;

    public ReferralServiceImpl(UserMapper userMapper,
                               UserReferralMapper referralMapper,
                               ReferralRegistrationRewardMapper registrationRewardMapper,
                               CreditService creditService) {
        this.userMapper = userMapper;
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
        Long inviterUserId = parseInviteCode(inviteCode);
        if (inviterUserId == null || inviterUserId.equals(inviteeUserId)) {
            return;
        }
        if (userMapper.selectById(inviterUserId) == null) {
            return;
        }
        UserReferral referral = new UserReferral();
        referral.setInviterUserId(inviterUserId);
        referral.setInviteeUserId(inviteeUserId);
        referral.setInviteCode(normalizeInviteCode(inviteCode));
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

    private Long parseInviteCode(String inviteCode) {
        String normalized = normalizeInviteCode(inviteCode);
        if (normalized == null) {
            return null;
        }
        String rawId = normalized.startsWith(INVITE_CODE_PREFIX)
                ? normalized.substring(INVITE_CODE_PREFIX.length())
                : normalized;
        if (!rawId.matches("\\d+")) {
            return null;
        }
        try {
            long id = Long.parseLong(rawId);
            return id > 0 ? id : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeInviteCode(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            return null;
        }
        return inviteCode.trim().toUpperCase();
    }
}
