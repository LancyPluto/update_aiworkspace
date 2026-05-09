package com.aiminilab.aitoolmarket.user.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
<<<<<<< HEAD
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsRequest;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;
import com.aiminilab.aitoolmarket.user.dto.AdminUserSummaryResponse;
import com.aiminilab.aitoolmarket.user.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
=======
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
import com.aiminilab.aitoolmarket.credit.dto.ManualCreditRequest;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.user.dto.AdminUserResponse;
import com.aiminilab.aitoolmarket.user.dto.UpdateUserStatusRequest;
import com.aiminilab.aitoolmarket.user.service.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
>>>>>>> origin/feature/backend-core
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
<<<<<<< HEAD
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
=======
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/users")
public class AdminUserController {

    private final UserAdminService userAdminService;
    private final CreditService creditService;

    public AdminUserController(UserAdminService userAdminService, CreditService creditService) {
        this.userAdminService = userAdminService;
        this.creditService = creditService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminUserResponse>> list(@RequestParam(required = false) String keyword,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(required = false) String userType,
                                                             @RequestParam(required = false) Integer pageNo,
                                                             @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(userAdminService.list(keyword, status, userType, pageNo, pageSize));
    }

    @GetMapping("/{userId}")
    public ApiResponse<AdminUserResponse> detail(@PathVariable Long userId) {
        return ApiResponse.success(userAdminService.detail(userId));
    }

    @PatchMapping("/{userId}/status")
    public ApiResponse<AdminUserResponse> updateStatus(@PathVariable Long userId,
                                                       @Valid @RequestBody UpdateUserStatusRequest request) {
        return ApiResponse.success(userAdminService.updateStatus(userId, request));
    }

    @GetMapping("/{userId}/credits/account")
    public ApiResponse<CreditAccountResponse> creditAccount(@PathVariable Long userId) {
        return ApiResponse.success(creditService.account(userId));
    }

    @GetMapping("/{userId}/credits/logs")
    public ApiResponse<PageResponse<CreditLogResponse>> creditLogs(@PathVariable Long userId,
                                                                   @RequestParam(required = false) String logType,
                                                                   @RequestParam(required = false) Integer pageNo,
                                                                   @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(creditService.logs(userId, logType, pageNo, pageSize));
    }

    @PostMapping("/{userId}/credits/manual-add")
    public ApiResponse<CreditAccountResponse> manualAdd(@PathVariable Long userId,
                                                        @Valid @RequestBody ManualCreditRequest request) {
        return ApiResponse.success(creditService.manualAdd(
                userId,
                request.amount(),
                request.reason(),
                AuthContext.get().userId()
        ));
    }

    @PostMapping("/{userId}/credits/manual-deduct")
    public ApiResponse<CreditAccountResponse> manualDeduct(@PathVariable Long userId,
                                                           @Valid @RequestBody ManualCreditRequest request) {
        return ApiResponse.success(creditService.manualDeduct(
                userId,
                request.amount(),
                request.reason(),
                AuthContext.get().userId()
        ));
>>>>>>> origin/feature/backend-core
    }
}
