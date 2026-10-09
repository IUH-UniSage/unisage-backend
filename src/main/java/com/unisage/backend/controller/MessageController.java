package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.CalculationFeedbackRequest;
import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.StartTurnRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.CalculationFeedbackResponse;
import com.unisage.backend.dto.response.ChatTurnResponse;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.security.InternalSecretFilter;
import com.unisage.backend.service.calculation.CalculationFeedbackService;
import com.unisage.backend.service.conversation.MessageService;
import com.unisage.backend.utils.CookieUtil;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
public class MessageController {

    /**
     * Mirrors the trust model {@code X-Forwarded-For} used to have here: only honored when
     * {@link InternalSecretFilter} has verified a valid {@code X-Internal-Secret}, i.e. only
     * {@code unisage-agent} calling this backend directly on behalf of a guest browser it
     * received the request from (it has no cookie of its own to forward otherwise).
     */
    private static final String GUEST_SESSION_TOKEN_HEADER = "X-Guest-Session-Token";

    /** contracts/chat-sse.md §5b: the feedback body is capped at 2 KB. */

    private final MessageService messageService;
    private final CalculationFeedbackService calculationFeedbackService;
    private final SecurityUtil securityUtil;
    private final CookieUtil cookieUtil;

    @PostMapping
    public ResponseEntity<ApiResponse<MessageResponse>> send(
            @Valid @RequestBody SendMessageRequest request,
            HttpServletRequest httpRequest) {
        UUID callerId = securityUtil.getCurrentUserIdOrNull();
        String guestSessionToken = extractGuestSessionToken(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(messageService.send(request, callerId, guestSessionToken)));
    }

    @PostMapping("/turn")
    public ResponseEntity<ApiResponse<ChatTurnResponse>> startTurn(
            @Valid @RequestBody StartTurnRequest request,
            HttpServletRequest httpRequest) {
        UUID callerId = securityUtil.getCurrentUserIdOrNull();
        String guestSessionToken = extractGuestSessionToken(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(messageService.startTurn(request, callerId, guestSessionToken)));
    }

    /** Đúng/Sai on one AI-computed (mode llm) calculation item; owner (user or guest session) only. */
    @PostMapping("/{messageId}/calculation-feedback")
    public ResponseEntity<ApiResponse<CalculationFeedbackResponse>> calculationFeedback(
            @PathVariable UUID messageId,
            @Valid @RequestBody CalculationFeedbackRequest request,
            HttpServletRequest httpRequest) {
        UUID callerId = securityUtil.getCurrentUserIdOrNull();
        String guestSessionToken = extractGuestSessionToken(httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                calculationFeedbackService.submit(
                        messageId, request, callerId, guestSessionToken,
                        httpRequest.getContentLengthLong())));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<MessageResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMessageRequest request) {
        return ResponseEntity.ok(ApiResponse.success(messageService.update(id, request)));
    }

    @GetMapping("/conversation/{conversationId}")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> getByConversation(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean context) {
        return ResponseEntity.ok(ApiResponse.success(
                messageService.getByConversation(conversationId, limit, context)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MessageResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(messageService.getById(id)));
    }

    /**
     * Most callers reach this endpoint straight from the browser via the gateway, which forwards
     * the {@code guest_session_id} cookie transparently — read directly. {@code unisage-agent} is
     * the one exception: it calls this backend directly, off-box, on behalf of a guest browser
     * whose session token it forwards via {@code X-Guest-Session-Token} instead (it has no cookie
     * jar of its own). That header is honored only when {@link InternalSecretFilter} has already
     * verified a valid {@code X-Internal-Secret} — an untrusted caller could otherwise supply an
     * arbitrary header value, though {@code GuestSessionService} would still reject anything that
     * doesn't hash to a real session, so this is defense-in-depth, not the sole safeguard.
     */
    private String extractGuestSessionToken(HttpServletRequest request) {
        boolean trustedInternalCaller = Boolean.TRUE.equals(
                request.getAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE));

        if (trustedInternalCaller) {
            String forwarded = request.getHeader(GUEST_SESSION_TOKEN_HEADER);
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded;
            }
        }
        return cookieUtil.extractGuestSessionTokenFromCookie(request);
    }
}
