package com.aiminilab.aitoolmarket.tool.dto;

public record FileUploadResponse(
        Long assetId,
        String fileId,
        String url,
        String name,
        String contentType,
        Long size,
        boolean historyVisible
) {
}
