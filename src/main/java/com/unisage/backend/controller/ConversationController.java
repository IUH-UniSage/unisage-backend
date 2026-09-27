package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.CreateConversationRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.ConversationResponse;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.service.conversation.ConversationService;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.guestsession.GuestSessionService.GuestSessionResolution;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;
import com.unisage.backend.utils.CookieUtil;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final SecurityUtil securityUtil;
    private final GuestSessionService guestSessionService;
    private final CookieUtil cookieUtil;
    private final SystemConfigResolver configResolver;

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationResponse>> create(
            @Valid @RequestBody CreateConversationRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        UUID ownerId = securityUtil.getCurrentUserIdOrNull();

        GuestSession guestSession = null;
        if (ownerId == null) {
            String cookieToken = cookieUtil.extractGuestSessionTokenFromCookie(httpRequest);
            GuestSessionResolution resolution = guestSessionService.resolveOrCreate(cookieToken);
            guestSession = resolution.session();
            writeGuestSessionCookie(httpResponse, resolution.rawToken());
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(conversationService.create(request, ownerId, guestSession)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ConversationResponse>>> getByUser(@RequestParam UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(conversationService.getByUser(userId)));
    }

    /**
     * Guest-only equivalent of {@link #getByUser}. Identity comes exclusively from the
     * {@code guest_session_id} cookie — never from a client-supplied id — and never mints a new
     * session: a missing/invalid/expired cookie simply yields an empty list.
     */
    @GetMapping("/guest")
    public ResponseEntity<ApiResponse<List<ConversationResponse>>> getByGuestSession(HttpServletRequest httpRequest) {
        String cookieToken = cookieUtil.extractGuestSessionTokenFromCookie(httpRequest);
        GuestSession guestSession = guestSessionService.resolveReadOnly(cookieToken).orElse(null);
        return ResponseEntity.ok(ApiResponse.success(conversationService.getByGuestSession(guestSession)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> softDelete(@PathVariable UUID id) {
        conversationService.softDelete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PatchMapping("/{id}/claim")
    public ResponseEntity<ApiResponse<ConversationResponse>> claim(@PathVariable UUID id) {
        UUID userId = securityUtil.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(conversationService.claim(id, userId)));
    }

    private void writeGuestSessionCookie(HttpServletResponse httpResponse, String rawToken) {
        // Same live setting GuestSessionServiceImpl uses for expiresAt, so the cookie never outlives
        // (or dies before) the session row an admin-edited TTL produces.
        int ttlDays = configResolver.getInt("maintenance.guest_session.ttl_days", 30);
        long maxAgeMs = ttlDays * 24L * 60 * 60 * 1000;
        ResponseCookie cookie = cookieUtil.createGuestSessionCookie(rawToken, maxAgeMs);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
