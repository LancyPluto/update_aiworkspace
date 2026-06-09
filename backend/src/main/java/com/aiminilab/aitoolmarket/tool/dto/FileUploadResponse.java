package com.aiminilab.aitoolmarket.tool.dto;

public record FileUploadResponse(
        String fileId,
        String url,
        String name,
        String contentType,
        Long size
) {
}
