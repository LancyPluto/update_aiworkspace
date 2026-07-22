package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.UserUploadAsset;

import java.time.LocalDateTime;

public record UserUploadAssetResponse(
        Long id,
        String fileId,
        String kind,
        String name,
        String contentType,
        Long size,
        String url,
        boolean historyVisible,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserUploadAssetResponse from(UserUploadAsset asset) {
        return new UserUploadAssetResponse(
                asset.getId(),
                asset.getFileId(),
                asset.getAssetKind(),
                asset.getOriginalFilename(),
                asset.getContentType(),
                asset.getFileSize(),
                asset.getUrl(),
                Boolean.TRUE.equals(asset.getHistoryVisible()),
                asset.getCreatedAt(),
                asset.getUpdatedAt()
        );
    }

    public UserUploadAssetResponse withRewrittenUrl(String rewrittenUrl) {
        return new UserUploadAssetResponse(id, fileId, kind, name, contentType, size, rewrittenUrl, historyVisible, createdAt, updatedAt);
    }
}
