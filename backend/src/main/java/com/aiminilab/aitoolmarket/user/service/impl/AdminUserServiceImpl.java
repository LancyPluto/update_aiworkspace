package com.aiminilab.aitoolmarket.user.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.GiftCardResponse;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;
import com.aiminilab.aitoolmarket.credit.entity.GiftCardPackage;
import com.aiminilab.aitoolmarket.credit.mapper.GiftCardPackageMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.GiftCardService;
import com.aiminilab.aitoolmarket.user.dto.AdminUserSummaryResponse;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.aiminilab.aitoolmarket.user.service.AdminUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminUserServiceImpl implements AdminUserService {

    private final UserMapper userMapper;
    private final CreditService creditService;
    private final GiftCardService giftCardService;
    private final GiftCardPackageMapper giftCardPackageMapper;

    public AdminUserServiceImpl(UserMapper userMapper,
                                CreditService creditService,
                                GiftCardService giftCardService,
                                GiftCardPackageMapper giftCardPackageMapper) {
        this.userMapper = userMapper;
        this.creditService = creditService;
        this.giftCardService = giftCardService;
        this.giftCardPackageMapper = giftCardPackageMapper;
    }

    @Override
    public PageResponse<AdminUserSummaryResponse> list() {
        List<AdminUserSummaryResponse> rows = userMapper.findForAdmin(null, "ACTIVE", null, 100, 0).stream()
                .map(this::toSummary)
                .toList();
        return new PageResponse<>(rows, rows.size());
    }

    @Override
    @Transactional
    public ManualAddCreditsResponse manualAddCredits(Long userId,
                                                     int amount,
                                                     String reason,
                                                     String operationId,
                                                     Long operatorId) {
        userMapper.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "User not found"));
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Gift card credits must be positive");
        }

        String normalizedReason = normalizeReason(reason);
        CreditAccountResponse before = creditService.account(userId);
        GiftCardPackage defaultPackage = findOrCreateAdminGiftPackage();
        GiftCardResponse giftCard = giftCardService.issueAdminGiftCard(
                userId,
                defaultPackage.getId(),
                amount,
                operationId,
                operatorId,
                normalizedReason
        );
        CreditAccountResponse after = creditService.account(userId);

        return new ManualAddCreditsResponse(
                userId,
                operationId.trim(),
                operatorId,
                amount,
                before.balance(),
                after.balance(),
                normalizedReason,
                giftCard,
                giftCard.createdAt()
        );
    }

    private GiftCardPackage findOrCreateAdminGiftPackage() {
        GiftCardPackage existing = giftCardPackageMapper.findByPackageCode("admin_default");
        if (existing != null) {
            if (!"HIDDEN".equals(existing.getStatus()) || !Integer.valueOf(0).equals(existing.getCredits())) {
                existing.setStatus("HIDDEN");
                existing.setCredits(0);
                existing.setUpdatedAt(LocalDateTime.now());
                giftCardPackageMapper.updateById(existing);
            }
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        GiftCardPackage defaultPackage = new GiftCardPackage();
        defaultPackage.setPackageCode("admin_default");
        defaultPackage.setPackageName("\u7ba1\u7406\u5458\u8d60\u9001\u793c\u54c1\u5361");
        defaultPackage.setCredits(0);
        defaultPackage.setPriceAmount(BigDecimal.ZERO);
        defaultPackage.setCurrency("CNY");
        defaultPackage.setCardTheme("green");
        defaultPackage.setCardType("CREDIT");
        defaultPackage.setRequiredMemberTier(null);
        defaultPackage.setStatus("HIDDEN");
        defaultPackage.setSortOrder(999);
        defaultPackage.setCreatedAt(now);
        defaultPackage.setUpdatedAt(now);
        giftCardPackageMapper.insert(defaultPackage);
        return defaultPackage;
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        return normalized.isEmpty() ? "管理员发放礼品卡" : normalized;
    }

    private AdminUserSummaryResponse toSummary(User user) {
        Integer balance = creditService.account(user.getId()).balance();
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getUserType(),
                user.getStatus(),
                balance,
                user.getCreatedAt()
        );
    }
}
