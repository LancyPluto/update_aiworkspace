package com.aiminilab.aitoolmarket.credit.wechat;

public record WechatPayCallbackHeaders(
        String serial,
        String signature,
        String timestamp,
        String nonce
) {
}
