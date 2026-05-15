package com.aiminilab.aitoolmarket.commerce.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.commerce.dto.CreatePaymentOrderRequest;
import com.aiminilab.aitoolmarket.commerce.payment.PaymentCallbackAck;
import com.aiminilab.aitoolmarket.commerce.service.CommercePlatformService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final CommercePlatformService service;

    public PaymentController(CommercePlatformService service) {
        this.service = service;
    }

    @PostMapping("/orders")
    public ApiResponse<Map<String, Object>> createOrder(@Valid @RequestBody CreatePaymentOrderRequest request) {
        return ApiResponse.success(service.createPaymentOrder(AuthContext.get().userId(), request));
    }

    @PostMapping("/orders/{orderId}/mock-pay")
    public ApiResponse<Map<String, Object>> mockPay(@PathVariable Long orderId) {
        return ApiResponse.success(service.mockPay(AuthContext.get().userId(), orderId));
    }

    @GetMapping("/orders")
    public ApiResponse<List<Map<String, Object>>> orders() {
        return ApiResponse.success(service.listOrders(AuthContext.get().userId()));
    }

    @PostMapping("/{channel}/callback")
    public ResponseEntity<String> callback(@PathVariable String channel,
                                           @RequestBody(required = false) String rawPayload,
                                           @RequestHeader Map<String, String> headers) {
        try {
            service.handlePaymentCallback(channel, rawPayload == null ? "{}" : rawPayload, headers);
            return ack(service.paymentCallbackAck(channel, true, "OK"));
        } catch (BusinessException exception) {
            return ack(service.paymentCallbackAck(channel, false, exception.getMessage()));
        } catch (RuntimeException exception) {
            log.error("Payment callback failed: channel={}", channel, exception);
            return ack(service.paymentCallbackAck(channel, false, "callback processing failed"));
        }
    }

    private ResponseEntity<String> ack(PaymentCallbackAck ack) {
        return ResponseEntity.status(ack.httpStatus())
                .contentType(MediaType.parseMediaType(ack.contentType()))
                .body(ack.body());
    }
}
