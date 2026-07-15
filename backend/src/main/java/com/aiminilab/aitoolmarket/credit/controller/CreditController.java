package com.aiminilab.aitoolmarket.credit.controller;

import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
import com.aiminilab.aitoolmarket.credit.dto.AlipayPayDiagnosticResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreateCustomRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.CreateRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.GiftCardPackageResponse;
import com.aiminilab.aitoolmarket.credit.dto.GiftCardResponse;
import com.aiminilab.aitoolmarket.credit.dto.GiftCardRedeemByCodeRequest;
import com.aiminilab.aitoolmarket.credit.dto.GiftCardTransferRequest;
import com.aiminilab.aitoolmarket.credit.dto.RechargeOrderResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePackageResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePaymentOptionsResponse;
import com.aiminilab.aitoolmarket.credit.realtime.CreditEventStreamService;
import com.aiminilab.aitoolmarket.credit.service.CreditRechargeService;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.GiftCardService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/credits")
public class CreditController {

    private final CreditService creditService;
    private final CreditRechargeService creditRechargeService;
    private final BillingService billingService;
    private final GiftCardService giftCardService;
    private final CreditEventStreamService creditEventStreamService;

    public CreditController(CreditService creditService,
                            CreditRechargeService creditRechargeService,
                            BillingService billingService,
                            GiftCardService giftCardService,
                            CreditEventStreamService creditEventStreamService) {
        this.creditService = creditService;
        this.creditRechargeService = creditRechargeService;
        this.billingService = billingService;
        this.giftCardService = giftCardService;
        this.creditEventStreamService = creditEventStreamService;
    }

    @GetMapping("/account")
    public ApiResponse<CreditAccountResponse> account() {
        return ApiResponse.success(creditService.account(AuthContext.get().userId()));
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events() {
        SseEmitter emitter = creditEventStreamService.subscribe(AuthContext.get().userId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
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

    @GetMapping("/recharge-payment-options")
    public ApiResponse<RechargePaymentOptionsResponse> rechargePaymentOptions() {
        return ApiResponse.success(creditRechargeService.paymentOptions());
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

    @GetMapping("/recharge-orders/{orderId}/alipay-diagnostic")
    public ApiResponse<AlipayPayDiagnosticResponse> alipayDiagnostic(@PathVariable Long orderId) {
        return ApiResponse.success(creditRechargeService.alipayDiagnostic(AuthContext.get().userId(), orderId));
    }

    @GetMapping("/gift-card-packages")
    public ApiResponse<List<GiftCardPackageResponse>> giftCardPackages() {
        return ApiResponse.success(giftCardService.packages());
    }

    @GetMapping("/gift-cards")
    public ApiResponse<List<GiftCardResponse>> myGiftCards(@RequestParam(required = false) String status) {
        return ApiResponse.success(giftCardService.myGiftCards(AuthContext.get().userId(), status));
    }

    @PostMapping("/gift-cards/{id}/redeem")
    public ApiResponse<GiftCardResponse> redeemGiftCard(@PathVariable Long id) {
        return ApiResponse.success(giftCardService.redeem(AuthContext.get().userId(), id));
    }

    @PostMapping("/gift-cards/redeem-by-code")
    public ApiResponse<GiftCardResponse> redeemGiftCardByCode(@Valid @RequestBody GiftCardRedeemByCodeRequest request) {
        return ApiResponse.success(giftCardService.redeemByCode(AuthContext.get().userId(), request.cardCode()));
    }

    @PostMapping("/gift-cards/{id}/gift")
    public ApiResponse<GiftCardResponse> giftCard(@PathVariable Long id, @Valid @RequestBody GiftCardTransferRequest request) {
        return ApiResponse.success(giftCardService.transfer(AuthContext.get().userId(), id, request.account()));
    }
}
