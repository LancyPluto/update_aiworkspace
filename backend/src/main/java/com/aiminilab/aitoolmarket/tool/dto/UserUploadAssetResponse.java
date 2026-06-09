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
                asset.getCreatedAt(),
                asset.getUpdatedAt()
        );
    }
}
