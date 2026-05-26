package com.aiminilab.aitoolmarket.credit.wechat;

public interface WechatNativePayClient {
    NativePrepayResponse createNativeOrder(NativePrepayRequest request);

    WechatPayNotification parseNotification(WechatPayCallbackHeaders headers, String body);
}
