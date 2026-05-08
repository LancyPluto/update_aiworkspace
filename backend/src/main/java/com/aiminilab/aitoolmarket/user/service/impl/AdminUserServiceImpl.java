package com.aiminilab.aitoolmarket.user.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.user.dto.AdminUserSummaryResponse;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.aiminilab.aitoolmarket.user.service.AdminUserService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminUserServiceImpl implements AdminUserService {

    private final UserMapper userMapper;
    private final CreditService creditService;

    public AdminUserServiceImpl(UserMapper userMapper, CreditService creditService) {
        this.userMapper = userMapper;
        this.creditService = creditService;
    }

    @Override
    public PageResponse<AdminUserSummaryResponse> list() {
        List<AdminUserSummaryResponse> rows = userMapper.findAllActive().stream()
                .map(this::toSummary)
                .toList();
        return new PageResponse<>(rows, rows.size());
    }

    @Override
    public ManualAddCreditsResponse manualAddCredits(Long userId, int amount, String reason, Long operatorId) {
        userMapper.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "用户不存在"));
        return creditService.manualAdd(userId, amount, reason, operatorId);
    }

    private AdminUserSummaryResponse toSummary(User user) {
        Integer balance = creditService.balance(user.getId());
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
