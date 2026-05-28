package com.aiminilab.aitoolmarket.credit.controller;

import com.aiminilab.aitoolmarket.credit.service.CreditRechargeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/pay/mock")
public class MockPayNotifyController {
    private static final Logger log = LoggerFactory.getLogger(MockPayNotifyController.class);

    private final CreditRechargeService creditRechargeService;

    public MockPayNotifyController(CreditRechargeService creditRechargeService) {
        this.creditRechargeService = creditRechargeService;
    }

    @PostMapping(value = "/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, String>> notify(@RequestParam Map<String, String> params) {
        try {
            creditRechargeService.handleMockPaymentNotification(
                    params.get("order_no"),
                    params.get("external_trade_no"),
                    params.getOrDefault("trade_status", "SUCCESS"),
                    params.get("total_amount")
            );
            return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "OK"));
        } catch (Exception exception) {
            log.warn("Mock payment notification failed, orderNo={}, message={}",
                    params.get("order_no"), exception.getMessage(), exception);
            return ResponseEntity.ok(Map.of("code", "FAIL", "message", "FAILED"));
        }
    }
}
