package com.aiminilab.aitoolmarket.auth.security;

import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Reads JWT from HttpOnly session cookies in MockMvc integration tests. */
public final class AuthTestTokens {

    private AuthTestTokens() {
    }

    public static String userJwtFrom(MvcResult result) {
        var cookie = result.getResponse().getCookie(AuthCookieSupport.USER_SESSION_COOKIE);
        assertNotNull(cookie, "Missing cookie " + AuthCookieSupport.USER_SESSION_COOKIE);
        return cookie.getValue();
    }

    public static String adminJwtFrom(MvcResult result) {
        var cookie = result.getResponse().getCookie(AuthCookieSupport.ADMIN_SESSION_COOKIE);
        assertNotNull(cookie, "Missing cookie " + AuthCookieSupport.ADMIN_SESSION_COOKIE);
        return cookie.getValue();
    }
}
