package com.unisage.backend.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CookieUtilTest {

    private final CookieUtil cookieUtil = new CookieUtil();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cookieUtil, "secure", true);
        ReflectionTestUtils.setField(cookieUtil, "sameSite", "Lax");
        ReflectionTestUtils.setField(cookieUtil, "path", "/");
    }

    @Test
    void createGuestSessionCookie_isHttpOnlySecureSameSiteLaxWithRootPath() {
        ResponseCookie cookie = cookieUtil.createGuestSessionCookie("raw-token", 1000L * 60 * 60 * 24 * 30);

        assertThat(cookie.getName()).isEqualTo(CookieUtil.GUEST_SESSION_COOKIE_NAME);
        assertThat(cookie.getValue()).isEqualTo("raw-token");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge().toSeconds()).isEqualTo(30L * 24 * 60 * 60);
    }
}
