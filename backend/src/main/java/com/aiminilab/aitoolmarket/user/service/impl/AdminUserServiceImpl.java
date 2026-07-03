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
        // 尝试查找已有的 admin_default 套餐
        List<GiftCardPackage> packages = giftCardPackageMapper.findActive();
        for (GiftCardPackage pkg : packages) {
            if ("admin_default".equals(pkg.getPackageCode())) {
                return pkg;
            }
        }
        
        // 如果不存在，创建一个新的默认套餐
        // 注意：这里需要手动插入，因为 Mapper 没有 create 方法
        // 实际生产环境应该在数据库初始化时就创建好这个套餐
        throw new BusinessException(ErrorCode.PARAM_ERROR, 
                "未找到管理员默认礼品卡套餐，请先在数据库中创建 package_code='admin_default' 的礼品卡套餐");
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
