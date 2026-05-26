package com.aiminilab.aitoolmarket.credit.controller;

import com.aiminilab.aitoolmarket.credit.service.CreditRechargeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/pay/wechat")
public class WechatPayNotifyController {
    private final CreditRechargeService creditRechargeService;

    public WechatPayNotifyController(CreditRechargeService creditRechargeService) {
        this.creditRechargeService = creditRechargeService;
    }

    @PostMapping("/native/notify")
    public ResponseEntity<Map<String, String>> nativeNotify(
            @RequestHeader("Wechatpay-Serial") String serial,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestBody String body
    ) {
        creditRechargeService.handleWechatNativePaymentNotification(serial, signature, timestamp, nonce, body);
        return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
    }
}
