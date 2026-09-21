package com.unisage.backend.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.UsageLimitResponse;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.usagelimit.UsageLimitService;
import com.unisage.backend.utils.CookieUtil;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/usage-limits")
@RequiredArgsConstructor
public class UsageLimitController {

    private final UsageLimitService usageLimitService;
    private final SecurityUtil securityUtil;
    private final GuestSessionService guestSessionService;
    private final CookieUtil cookieUtil;

    /**
     * Caller's own remaining quota. Identity comes only from the JWT (user) or the guest-session
     * cookie (guest), never from the request, and a guest without a valid session is reported as
     * having used nothing instead of minting a session.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UsageLimitResponse>> getMine(HttpServletRequest httpRequest) {
        UUID userId = securityUtil.getCurrentUserIdOrNull();

        GuestSession guestSession = null;
        if (userId == null) {
            String cookieToken = cookieUtil.extractGuestSessionTokenFromCookie(httpRequest);
            guestSession = guestSessionService.resolveReadOnly(cookieToken).orElse(null);
        }

        return ResponseEntity.ok(ApiResponse.success(usageLimitService.getUsage(userId, guestSession)));
    }
}
