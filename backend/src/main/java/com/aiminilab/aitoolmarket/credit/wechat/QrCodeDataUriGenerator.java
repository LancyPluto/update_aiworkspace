package com.aiminilab.aitoolmarket.credit.wechat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

@Component
public class QrCodeDataUriGenerator {
    public String generate(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            StringBuilder svg = new StringBuilder();
            svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                    .append(width).append(' ').append(height).append("\" shape-rendering=\"crispEdges\">")
                    .append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>");
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (matrix.get(x, y)) {
                        svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                                .append("\" width=\"1\" height=\"1\" fill=\"#000\"/>");
                    }
                }
            }
            svg.append("</svg>");
            return "data:image/svg+xml;charset=UTF-8," + UriUtils.encode(svg.toString(), StandardCharsets.UTF_8);
        } catch (WriterException exception) {
            throw new IllegalStateException("failed to generate WeChat Native payment QR code", exception);
        }
    }
}
