package com.aiminilab.aitoolmarket.credit.controller;

import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreateCustomRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.CreateRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.RechargeOrderResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePackageResponse;
import com.aiminilab.aitoolmarket.credit.service.CreditRechargeService;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/credits")
public class CreditController {

    private final CreditService creditService;
    private final CreditRechargeService creditRechargeService;
    private final BillingService billingService;

    public CreditController(CreditService creditService,
                            CreditRechargeService creditRechargeService,
                            BillingService billingService) {
        this.creditService = creditService;
        this.creditRechargeService = creditRechargeService;
        this.billingService = billingService;
    }

    @GetMapping("/account")
    public ApiResponse<CreditAccountResponse> account() {
        return ApiResponse.success(creditService.account(AuthContext.get().userId()));
    }

    @GetMapping("/logs")
    public ApiResponse<PageResponse<CreditLogResponse>> logs(@RequestParam(required = false) String logType,
                                                             @RequestParam(required = false) Integer pageNo,
                                                             @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(creditService.logs(AuthContext.get().userId(), logType, pageNo, pageSize));
    }

    @GetMapping("/usage-logs")
    public ApiResponse<PageResponse<BillingUsageLogResponse>> usageLogs(@RequestParam(required = false) Integer pageNo,
                                                                        @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(billingService.logs(pageNo, pageSize, AuthContext.get().userId(), null,
                null, null, null, null, null, null));
    }

    @GetMapping("/recharge-packages")
    public ApiResponse<List<RechargePackageResponse>> rechargePackages() {
        return ApiResponse.success(creditRechargeService.packages());
    }

    @PostMapping("/recharge-orders")
    public ApiResponse<RechargeOrderResponse> createRechargeOrder(@Valid @RequestBody CreateRechargeOrderRequest request) {
        return ApiResponse.success(creditRechargeService.createOrder(AuthContext.get().userId(), request));
    }

    @PostMapping("/recharge-orders/custom")
    public ApiResponse<RechargeOrderResponse> createCustomRechargeOrder(@Valid @RequestBody CreateCustomRechargeOrderRequest request) {
        return ApiResponse.success(creditRechargeService.createCustomOrder(AuthContext.get().userId(), request));
    }

    @GetMapping("/recharge-orders/{orderId}")
    public ApiResponse<RechargeOrderResponse> rechargeOrder(@PathVariable Long orderId) {
        return ApiResponse.success(creditRechargeService.getOrder(AuthContext.get().userId(), orderId));
    }

    @PostMapping("/recharge-orders/{orderId}/mock-pay-success")
    public ApiResponse<RechargeOrderResponse> mockPaySuccess(@PathVariable Long orderId) {
        return ApiResponse.success(creditRechargeService.mockPaySuccess(AuthContext.get().userId(), orderId));
    }
}
