package com.aiminilab.aitoolmarket.auth.dto;

/**
 * JWT 写入 HttpOnly Cookie；{@link LoginResponse#accessToken()} 与 Cookie 中 JWT 相同，供前端 Bearer 回退。
 */
public record AuthenticatedSession(String jwt, LoginResponse body) {
}
