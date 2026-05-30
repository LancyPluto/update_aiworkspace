package com.aiminilab.aitoolmarket.user.dto;

public record CommunitySettingsRequest(
        Boolean autoPublishAssets,
        Boolean promptPublicByDefault,
        String bio
) {
}
