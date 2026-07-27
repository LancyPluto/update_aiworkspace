package com.aiminilab.aitoolmarket.ppt.dto;

import java.time.LocalDateTime;

public record PptExportView(
        Long exportId,
        Long projectId,
        String exportType,
        String status,
        String fileName,
        String contentType,
        Long fileSize,
        String downloadUrl,
        LocalDateTime createdAt
) {
}
