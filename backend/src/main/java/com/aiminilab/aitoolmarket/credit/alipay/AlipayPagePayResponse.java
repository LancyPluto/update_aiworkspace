package com.aiminilab.aitoolmarket.credit.alipay;

/**
 * PRECREATE mode returns qr_code for scanner payment.
 * PAGE mode returns a launch path that renders alipay.trade.page.pay HTML form.
 */
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
