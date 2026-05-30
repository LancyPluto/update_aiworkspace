package com.aiminilab.aitoolmarket.community.dto;

import java.util.List;

public record CommunityCreatorResponse(
        PublicUserProfileResponse profile,
        Long sameStyleCount,
        Long featuredCount,
        List<CommunityPostResponse> featuredPosts,
        List<CommunityPostResponse> recentPosts
) {
}
