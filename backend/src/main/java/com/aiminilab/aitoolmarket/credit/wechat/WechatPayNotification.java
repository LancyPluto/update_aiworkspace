package com.aiminilab.aitoolmarket.credit.wechat;

public record WechatPayNotification(
        String appid,
        String mchid,
        String outTradeNo,
        String transactionId,
        String tradeType,
        String tradeState,
        int totalAmountFen,
        String currency
) {
}
