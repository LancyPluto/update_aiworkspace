package com.aiminilab.aitoolmarket.credit.wechat;

public interface WechatNativePayClient {
    NativePrepayResponse createNativeOrder(NativePrepayRequest request);

    WechatPayNotification queryNativeOrder(String orderNo);

    WechatPayNotification parseNotification(WechatPayCallbackHeaders headers, String body);
}
