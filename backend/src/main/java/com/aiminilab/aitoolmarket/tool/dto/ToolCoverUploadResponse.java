package com.aiminilab.aitoolmarket.tool.dto;

public record ToolCoverUploadResponse(
        String url,
        String filename,
        String contentType,
        long fileSize
) {
}
