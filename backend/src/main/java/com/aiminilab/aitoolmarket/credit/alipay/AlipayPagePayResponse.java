package com.aiminilab.aitoolmarket.credit.alipay;

/**
 * For Alipay QR pay, this is the qr_code string returned by alipay.trade.precreate.
 * It should be encoded into an image QR code on frontend/backend.
 */
public record AlipayPagePayResponse(String qrCode) {
}
