package com.aiminilab.aitoolmarket.user.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.user.dto.AdminUserResponse;
import com.aiminilab.aitoolmarket.user.dto.UpdateUserStatusRequest;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.aiminilab.aitoolmarket.user.service.UserAdminService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserAdminServiceImpl implements UserAdminService {

    private final UserMapper userMapper;
    private final CreditService creditService;

    public UserAdminServiceImpl(UserMapper userMapper, CreditService creditService) {
        this.userMapper = userMapper;
        this.creditService = creditService;
    }

    @Override
    public PageResponse<AdminUserResponse> list(String keyword, String status, String userType,
                                                Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<AdminUserResponse> list = userMapper.findForAdmin(keyword, status, userType, normalizedPageSize, offset)
                .stream()
                .map(this::toAdminUser)
                .toList();
        long total = userMapper.countForAdmin(keyword, status, userType);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    public AdminUserResponse detail(Long userId) {
        return toAdminUser(findUser(userId));
    }

    @Override
    public AdminUserResponse updateStatus(Long userId, UpdateUserStatusRequest request) {
        String status = request.status().trim();
        if (!UserStatus.ACTIVE.name().equals(status) && !UserStatus.DISABLED.name().equals(status)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户状态不合法");
        }
        findUser(userId);
        userMapper.updateStatus(userId, status);
        return detail(userId);
    }

    private AdminUserResponse toAdminUser(User user) {
        return AdminUserResponse.of(user, creditService.account(user.getId()));
    }

    private User findUser(Long userId) {
        return userMapper.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "用户不存在"));
    }
}
