package com.unisage.backend.controller;

import com.unisage.backend.dto.request.LoginRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.AuthResponse;
import com.unisage.backend.service.auth.AuthService;
import com.unisage.backend.utils.CookieUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final CookieUtil cookieUtil;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse httpResponse) {

        log.info("POST /api/auth/login - code: {}", request.code());

        AuthResponse response = authService.login(request);

        ResponseCookie accessCookie = cookieUtil.createAccessTokenCookie(
                response.accessToken(), 86400000L);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());

        ResponseCookie refreshCookie = cookieUtil.createRefreshTokenCookie(
                response.refreshToken(), response.refreshTokenExpirationMs());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        log.info("POST /api/auth/refresh");

        String refreshToken = cookieUtil.extractRefreshTokenFromCookie(httpRequest);

        AuthResponse response = authService.refresh(refreshToken);

        ResponseCookie accessCookie = cookieUtil.createAccessTokenCookie(
                response.accessToken(), 86400000L);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());

        ResponseCookie refreshCookie = cookieUtil.createRefreshTokenCookie(
                response.refreshToken(), response.refreshTokenExpirationMs());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletResponse httpResponse) {
        log.info("POST /api/auth/logout");

        ResponseCookie accessCookie = cookieUtil.clearAccessTokenCookie();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());

        ResponseCookie refreshCookie = cookieUtil.clearRefreshTokenCookie();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
