package com.aiminilab.aitoolmarket.admin.dto;

public record CustomerServiceQrUploadResponse(
        String url,
        String filename,
        String contentType,
        long fileSize
) {
}
