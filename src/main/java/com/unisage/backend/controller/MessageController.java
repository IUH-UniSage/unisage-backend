package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.security.InternalSecretFilter;
import com.unisage.backend.service.conversation.MessageService;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final SecurityUtil securityUtil;

    @PostMapping
    public ResponseEntity<ApiResponse<MessageResponse>> send(
            @Valid @RequestBody SendMessageRequest request,
            HttpServletRequest httpRequest) {
        UUID callerId = securityUtil.getCurrentUserIdOrNull();
        String ipAddress = extractClientIp(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(messageService.send(request, callerId, ipAddress)));
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
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.success(messageService.getByConversation(conversationId, limit)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MessageResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(messageService.getById(id)));
    }

    /**
     * Most callers of this endpoint reach it straight from the socket (end-user browsers via the
     * gateway, whose own address isn't relevant here). {@code unisage-agent} is the one exception:
     * it calls this backend directly, off-box, on behalf of an end-user whose IP it forwards via
     * {@code X-Forwarded-For} — but that header is only trustworthy when {@link InternalSecretFilter}
     * has already verified the caller carried a valid {@code X-Internal-Secret}. An untrusted
     * caller could otherwise spoof {@code X-Forwarded-For} to impersonate another guest's IP and
     * hijack their conversation, so it's ignored unless the request is marked trusted.
     */
    private String extractClientIp(HttpServletRequest request) {
        boolean trustedInternalCaller = Boolean.TRUE.equals(
                request.getAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE));

        if (trustedInternalCaller) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
