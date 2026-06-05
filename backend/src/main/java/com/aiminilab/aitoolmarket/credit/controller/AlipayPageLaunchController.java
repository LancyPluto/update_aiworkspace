package com.aiminilab.aitoolmarket.credit.controller;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayClient;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayRequest;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pay/alipay")
public class AlipayPageLaunchController {
    private final CreditRechargeOrderMapper orderMapper;
    private final AlipayPagePayClient alipayPagePayClient;

    public AlipayPageLaunchController(CreditRechargeOrderMapper orderMapper,
                                      AlipayPagePayClient alipayPagePayClient) {
        this.orderMapper = orderMapper;
        this.alipayPagePayClient = alipayPagePayClient;
    }

    @GetMapping(value = "/page/launch", produces = MediaType.TEXT_HTML_VALUE)
    public String launch(@RequestParam("orderNo") String orderNo) {
        CreditRechargeOrder order = orderMapper.findByOrderNo(orderNo);
        if (order == null || !"ALIPAY_PAGE".equals(order.getPaymentChannel())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay recharge order not found");
        }
        String subject = order.getPackageId() == null
                ? "Custom credits recharge " + order.getCredits() + " credits"
                : "AI Tool Market credits recharge";
        return alipayPagePayClient.buildPagePayHtml(new AlipayPagePayRequest(
                order.getOrderNo(),
                subject,
                order.getPriceAmount(),
                order.getExpiresAt()
        ));
    }
}
