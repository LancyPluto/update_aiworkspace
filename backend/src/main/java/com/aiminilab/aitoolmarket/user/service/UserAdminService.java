package com.aiminilab.aitoolmarket.user.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.user.dto.AdminUserResponse;
import com.aiminilab.aitoolmarket.user.dto.UpdateUserStatusRequest;

public interface UserAdminService {
    PageResponse<AdminUserResponse> list(String keyword, String status, String userType, Integer pageNo, Integer pageSize);

    AdminUserResponse detail(Long userId);

    AdminUserResponse updateStatus(Long userId, UpdateUserStatusRequest request);
}
