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
@RequestMapping("/api/v1/pay/alipay")
public class AlipayPayNotifyController {
    private static final Logger log = LoggerFactory.getLogger(AlipayPayNotifyController.class);

    private final CreditRechargeService creditRechargeService;

    public AlipayPayNotifyController(CreditRechargeService creditRechargeService) {
        this.creditRechargeService = creditRechargeService;
    }

    @PostMapping(value = "/page/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> pageNotify(@RequestParam Map<String, String> params) {
        try {
            creditRechargeService.handleAlipayPagePaymentNotification(params);
            return ResponseEntity.ok("success");
        } catch (Exception exception) {
            log.warn("Alipay page payment notification failed, outTradeNo={}, message={}",
                    params.get("out_trade_no"), exception.getMessage(), exception);
            return ResponseEntity.ok("failure");
        }
    }
}
