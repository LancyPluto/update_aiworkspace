package com.aiminilab.aitoolmarket.community.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CommunityCollectionResponse(
        Long id,
        String name,
        Boolean defaultCollection,
        Long itemCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<CommunityPostResponse> items
) {
}
