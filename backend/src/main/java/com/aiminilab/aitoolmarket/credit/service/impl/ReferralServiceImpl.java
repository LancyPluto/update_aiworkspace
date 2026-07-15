package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.entity.ReferralReward;
import com.aiminilab.aitoolmarket.credit.entity.UserReferral;
import com.aiminilab.aitoolmarket.credit.mapper.ReferralRewardMapper;
import com.aiminilab.aitoolmarket.credit.mapper.UserReferralMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.ReferralService;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
public class ReferralServiceImpl implements ReferralService {
    private static final String INVITE_CODE_PREFIX = "WLCLOUD";
    private static final BigDecimal RECHARGE_REWARD_RATE = new BigDecimal("0.10");

    private final UserMapper userMapper;
    private final UserReferralMapper referralMapper;
    private final ReferralRewardMapper rewardMapper;
    private final CreditService creditService;

    public ReferralServiceImpl(UserMapper userMapper,
                               UserReferralMapper referralMapper,
                               ReferralRewardMapper rewardMapper,
                               CreditService creditService) {
        this.userMapper = userMapper;
        this.referralMapper = referralMapper;
        this.rewardMapper = rewardMapper;
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
        }
    }

    @Override
    @Transactional
    public void rewardRechargeIfNeeded(CreditRechargeOrder order) {
        if (order == null || order.getId() == null || order.getUserId() == null) {
            return;
        }
        if (order.getCredits() == null || order.getCredits() <= 0) {
            return;
        }
        if (order.getOrderType() != null
                && !"CREDITS".equals(order.getOrderType())
                && !"MEMBERSHIP".equals(order.getOrderType())) {
            return;
        }
        UserReferral referral = referralMapper.findByInviteeUserId(order.getUserId());
        if (referral == null) {
            return;
        }
        if (rewardMapper.findByRechargeOrderId(order.getId()) != null) {
            return;
        }
        int rewardCredits = BigDecimal.valueOf(order.getCredits())
                .multiply(RECHARGE_REWARD_RATE)
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        if (rewardCredits <= 0) {
            return;
        }

        ReferralReward reward = new ReferralReward();
        reward.setReferralId(referral.getId());
        reward.setInviterUserId(referral.getInviterUserId());
        reward.setInviteeUserId(referral.getInviteeUserId());
        reward.setRechargeOrderId(order.getId());
        reward.setRewardCredits(rewardCredits);
        reward.setRewardRate(RECHARGE_REWARD_RATE);
        reward.setStatus("CREDITED");
        LocalDateTime now = LocalDateTime.now();
        reward.setCreatedAt(now);
        reward.setUpdatedAt(now);
        try {
            rewardMapper.insert(reward);
        } catch (DuplicateKeyException ignored) {
            return;
        }

        creditService.referralBonusAdd(
                referral.getInviterUserId(),
                order.getId(),
                rewardCredits,
                "邀请好友充值奖励：" + order.getOrderNo()
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
