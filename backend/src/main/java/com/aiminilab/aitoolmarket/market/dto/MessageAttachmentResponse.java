package com.aiminilab.aitoolmarket.market.dto;

import com.aiminilab.aitoolmarket.market.entity.AiMarketMessageAttachment;

public record MessageAttachmentResponse(
        String fileId,
        String name,
        long size,
        String type
) {
    public static MessageAttachmentResponse from(AiMarketMessageAttachment attachment) {
        return new MessageAttachmentResponse(
                attachment.getFileId(),
                attachment.getFileName(),
                attachment.getFileSize(),
                attachment.getContentType()
        );
    }
}
