package com.aiminilab.aitoolmarket.auth.security;

public record AuthUser(
        Long userId,
        String username,
        String userType
) {
}
