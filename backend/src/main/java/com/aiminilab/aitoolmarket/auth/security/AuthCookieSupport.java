package com.aiminilab.aitoolmarket.auth.security;

import com.aiminilab.aitoolmarket.config.AppProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AuthCookieSupport {

    public static final String USER_SESSION_COOKIE = "ATM_USER_SESSION";
    public static final String ADMIN_SESSION_COOKIE = "ATM_ADMIN_SESSION";

    /** 使用根路径，避免部分浏览器/代理下 Path 过窄导致后续请求未携带 Cookie。 */
    static final String USER_COOKIE_PATH = "/";
    static final String ADMIN_COOKIE_PATH = "/";

    private final JwtTokenProvider jwtTokenProvider;
    private final AppProperties appProperties;

    public AuthCookieSupport(JwtTokenProvider jwtTokenProvider, AppProperties appProperties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.appProperties = appProperties;
    }

    public ResponseCookie userSessionCookie(String jwt) {
        return sessionCookie(USER_SESSION_COOKIE, USER_COOKIE_PATH, jwt);
    }

    public ResponseCookie adminSessionCookie(String jwt) {
        return sessionCookie(ADMIN_SESSION_COOKIE, ADMIN_COOKIE_PATH, jwt);
    }

    public ResponseCookie deleteUserCookie() {
        return deleteCookie(USER_SESSION_COOKIE, USER_COOKIE_PATH);
    }

    public ResponseCookie deleteAdminCookie() {
        return deleteCookie(ADMIN_SESSION_COOKIE, ADMIN_COOKIE_PATH);
    }

    private ResponseCookie sessionCookie(String name, String path, String jwt) {
        Duration maxAge = Duration.ofMinutes(jwtTokenProvider.getExpireMinutes());
        return ResponseCookie.from(name, jwt)
                .path(path)
                .httpOnly(true)
                .secure(resolveSecure())
                .sameSite(appProperties.getAuth().getCookieSameSite())
                .maxAge(maxAge)
                .build();
    }

    private ResponseCookie deleteCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .path(path)
                .httpOnly(true)
                .secure(resolveSecure())
                .sameSite(appProperties.getAuth().getCookieSameSite())
                .maxAge(Duration.ZERO)
                .build();
    }

    private boolean resolveSecure() {
        Boolean override = appProperties.getAuth().getCookieSecure();
        return override != null ? override : appProperties.isProductionMode();
    }
}
