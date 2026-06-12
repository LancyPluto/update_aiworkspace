package com.aiminilab.aitoolmarket.credit.alipay;

/** 电脑网站支付：返回 launch 路径，由服务端渲染 alipay.trade.page.pay 跳转表单。 */
public record AlipayPagePayResponse(String qrCode, String redirectPath, String mode) {
    public static final String MODE_QR = "QR";
    public static final String MODE_PAGE = "PAGE";

    public static AlipayPagePayResponse qr(String qrCode) {
        return new AlipayPagePayResponse(qrCode, null, MODE_QR);
    }

    public static AlipayPagePayResponse pageRedirect(String redirectPath) {
        return new AlipayPagePayResponse(null, redirectPath, MODE_PAGE);
    }
}
