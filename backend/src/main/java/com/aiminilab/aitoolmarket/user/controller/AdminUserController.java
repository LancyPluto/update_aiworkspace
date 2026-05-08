package com.aiminilab.aitoolmarket.user.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsRequest;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;
import com.aiminilab.aitoolmarket.user.dto.AdminUserSummaryResponse;
import com.aiminilab.aitoolmarket.user.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<AdminUserSummaryResponse>> users() {
        return ApiResponse.success(adminUserService.list());
    }

    @PostMapping("/users/{userId}/credits/manual-add")
    public ApiResponse<ManualAddCreditsResponse> manualAdd(@PathVariable Long userId,
                                                           @Valid @RequestBody ManualAddCreditsRequest request) {
        String reason = request.reason() == null || request.reason().isBlank()
                ? "运营手动加算力"
                : request.reason();
        return ApiResponse.success(
                adminUserService.manualAddCredits(userId, request.amount(), reason, AuthContext.get().userId())
        );
    }
}
