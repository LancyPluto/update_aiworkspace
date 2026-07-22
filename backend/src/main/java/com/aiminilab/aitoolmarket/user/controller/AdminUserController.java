package com.aiminilab.aitoolmarket.user.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;
import com.aiminilab.aitoolmarket.credit.dto.ManualCreditRequest;
import com.aiminilab.aitoolmarket.credit.dto.ManualGiftCardIssueRequest;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.user.dto.AdminUserResponse;
import com.aiminilab.aitoolmarket.user.dto.UpdateUserStatusRequest;
import com.aiminilab.aitoolmarket.user.service.AdminUserService;
import com.aiminilab.aitoolmarket.user.service.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/users")
public class AdminUserController {

    private final UserAdminService userAdminService;
    private final CreditService creditService;
    private final AdminUserService adminUserService;

    public AdminUserController(UserAdminService userAdminService,
                               CreditService creditService,
                               AdminUserService adminUserService) {
        this.userAdminService = userAdminService;
        this.creditService = creditService;
        this.adminUserService = adminUserService;
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
        return ApiResponse.success(creditService.logs(userId, logType, pageNo, pageSize, true));
    }

    @PostMapping("/{userId}/credits/manual-add")
    public ApiResponse<ManualAddCreditsResponse> manualAdd(@PathVariable Long userId,
                                                           @Valid @RequestBody ManualGiftCardIssueRequest request) {
        return ApiResponse.success(adminUserService.manualAddCredits(
                userId,
                request.amount(),
                request.reason(),
                request.operationId(),
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
    }
}
