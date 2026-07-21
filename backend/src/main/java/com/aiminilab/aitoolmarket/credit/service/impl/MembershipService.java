package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.RechargeOrderStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargePackage;
import com.aiminilab.aitoolmarket.credit.entity.UserMembership;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.credit.mapper.UserMembershipMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.support.MembershipTier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class MembershipService {
    private final UserMembershipMapper membershipMapper;
    private final CreditRechargeOrderMapper orderMapper;
    private final CreditService creditService;

    public MembershipService(UserMembershipMapper membershipMapper,
                             CreditRechargeOrderMapper orderMapper,
                             CreditService creditService) {
        this.membershipMapper = membershipMapper;
        this.orderMapper = orderMapper;
        this.creditService = creditService;
    }

    @Transactional
    public CreditRechargeOrder reserveOrder(Long userId,
                                            CreditRechargeOrder candidate,
                                            CreditRechargePackage rechargePackage) {
        LocalDateTime now = LocalDateTime.now();
        membershipMapper.ensureSlot(userId, now);
        UserMembership membership = membershipMapper.lockByUserId(userId);
        if (membership == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "membership slot unavailable");
        }
        if ("ACTIVE".equals(membership.getStatus())
                && membership.getExpiresAt() != null
                && membership.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.MEMBERSHIP_ACTIVE,
                    "当前会员尚未到期，到期后才能购买新的会员计划",
                    Map.of("expiresAt", membership.getExpiresAt()));
        }
        if ("ACTIVE".equals(membership.getStatus())
                && membership.getExpiresAt() != null
                && !membership.getExpiresAt().isAfter(now)) {
            creditService.expireMembershipIfNeeded(userId);
            membership = membershipMapper.lockByUserId(userId);
        }
        if ("PENDING".equals(membership.getStatus()) && membership.getOrderId() != null) {
            CreditRechargeOrder pending = orderMapper.selectById(membership.getOrderId());
            if (pending != null && (RechargeOrderStatus.WAITING_PAYMENT.name().equals(pending.getStatus())
                    || RechargeOrderStatus.PAID.name().equals(pending.getStatus()))) {
                boolean samePurchase = rechargePackage.getId().equals(pending.getPackageId())
                        && candidate.getPaymentChannel().equals(pending.getPaymentChannel());
                if (samePurchase) {
                    return pending;
                }
                throw new BusinessException(ErrorCode.MEMBERSHIP_ORDER_PENDING,
                        "已有待支付会员订单，请先完成原订单",
                        Map.of("orderId", pending.getId()));
            }
        }

        orderMapper.insert(candidate);
        membership.setStatus("PENDING");
        membership.setPackageId(rechargePackage.getId());
        membership.setPackageCode(rechargePackage.getPackageCode());
        membership.setOrderId(candidate.getId());
        membership.setStartedAt(null);
        membership.setExpiresAt(null);
        membership.setUpdatedAt(now);
        membershipMapper.updateState(membership);
        return candidate;
    }

    @Transactional
    public void activate(CreditRechargeOrder order) {
        LocalDateTime now = LocalDateTime.now();
        membershipMapper.ensureSlot(order.getUserId(), now);
        UserMembership membership = membershipMapper.lockByUserId(order.getUserId());
        if ("ACTIVE".equals(membership.getStatus()) && order.getId().equals(membership.getOrderId())) {
            return;
        }
        if ("ACTIVE".equals(membership.getStatus()) && membership.getExpiresAt() != null
                && membership.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.MEMBERSHIP_ACTIVE, "用户已有生效中的会员计划");
        }
        LocalDateTime startedAt = order.getPaidAt() == null ? now : order.getPaidAt();
        int validityDays = order.getValidityDaysSnapshot() == null ? 0 : order.getValidityDaysSnapshot();
        membership.setStatus("ACTIVE");
        membership.setPackageId(order.getPackageId());
        membership.setPackageCode(order.getPackageCodeSnapshot());
        membership.setOrderId(order.getId());
        membership.setStartedAt(startedAt);
        membership.setExpiresAt(startedAt.plusDays(validityDays));
        membership.setUpdatedAt(now);
        membershipMapper.updateState(membership);
    }

    @Transactional
    public void releasePending(Long userId, Long orderId) {
        UserMembership membership = membershipMapper.lockByUserId(userId);
        if (membership == null || !"PENDING".equals(membership.getStatus())
                || !orderId.equals(membership.getOrderId())) {
            return;
        }
        membership.setStatus("NONE");
        membership.setPackageId(null);
        membership.setPackageCode(null);
        membership.setOrderId(null);
        membership.setStartedAt(null);
        membership.setExpiresAt(null);
        membership.setUpdatedAt(LocalDateTime.now());
        membershipMapper.updateState(membership);
    }

    public UserMembership current(Long userId) {
        creditService.expireMembershipIfNeeded(userId);
        return membershipMapper.findByUserId(userId);
    }

    public void requireActiveTierAtLeast(Long userId, String requiredTierCode, String operation) {
        MembershipTier requiredTier = MembershipTier.fromCode(requiredTierCode)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SYSTEM_ERROR,
                        "会员礼品卡等级配置无效"));

        UserMembership membership = current(userId);
        LocalDateTime now = LocalDateTime.now();
        MembershipTier currentTier = membership == null
                ? null
                : MembershipTier.fromPackageCode(membership.getPackageCode()).orElse(null);
        boolean active = membership != null
                && "ACTIVE".equals(membership.getStatus())
                && membership.getExpiresAt() != null
                && membership.getExpiresAt().isAfter(now);
        if (!active || currentTier == null || !currentTier.meetsOrExceeds(requiredTier)) {
            String action = operation == null || operation.isBlank() ? "使用" : operation.trim();
            throw new BusinessException(
                    ErrorCode.FORBIDDEN,
                    action + "此会员礼品卡需要有效的" + requiredTier.displayName() + "或更高等级会员");
        }
    }
}
