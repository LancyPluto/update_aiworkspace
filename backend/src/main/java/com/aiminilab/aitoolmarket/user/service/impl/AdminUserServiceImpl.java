package com.aiminilab.aitoolmarket.user.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
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
    public ManualAddCreditsResponse manualAddCredits(Long userId, int amount, String reason, Long operatorId) {
        userMapper.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "用户不存在"));
        
        // 获取当前用户的算力账户信息（用于返回前后余额）
        CreditAccountResponse before = creditService.account(userId);
        
        // 查找或创建一个默认的礼品卡套餐（用于管理员手动加算力）
        GiftCardPackage defaultPackage = findOrCreateAdminGiftPackage(amount);
        
        // 创建礼品卡记录（不直接增加算力）
        giftCardService.createGiftCardFromOrder(
                userId,
                null, // orderId 为 null，表示是管理员手动添加
                defaultPackage.getId(),
                amount
        );
        
        // 获取更新后的账户信息
        CreditAccountResponse after = creditService.account(userId);
        
        return new ManualAddCreditsResponse(
                userId,
                amount,
                before.balance(),
                after.balance(),
                reason != null ? reason : "管理员手动添加礼品卡",
                LocalDateTime.now()
        );
    }
    
    /**
     * 查找或创建管理员专用的默认礼品卡套餐
     */
    private GiftCardPackage findOrCreateAdminGiftPackage(int credits) {
        // admin_default 可能是 HIDDEN，不能只查 ACTIVE，否则生产已有隐藏套餐时会重复插入。
        GiftCardPackage existing = giftCardPackageMapper.findByPackageCode("admin_default");
        if (existing != null) {
            return existing;
        }
        
        // 如果不存在，创建一个新的默认套餐
        // 这个套餐的 credits 和 price 都是0，实际金额由 createGiftCardFromOrder 的参数决定
        GiftCardPackage defaultPackage = new GiftCardPackage();
        LocalDateTime now = LocalDateTime.now();
        defaultPackage.setPackageCode("admin_default");
        defaultPackage.setPackageName("管理员赠送礼品卡");
        defaultPackage.setCredits(credits); // 设置为当前要赠送的金额
        defaultPackage.setPriceAmount(BigDecimal.ZERO); // 价格为0，因为是管理员赠送
        defaultPackage.setCurrency("CNY");
        defaultPackage.setCardTheme("green");
        defaultPackage.setStatus("HIDDEN");
        defaultPackage.setSortOrder(999);
        defaultPackage.setCreatedAt(now);
        defaultPackage.setUpdatedAt(now);
        
        giftCardPackageMapper.insert(defaultPackage);
        
        return defaultPackage;
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
