package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.CreateConversationRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.ConversationResponse;
import com.unisage.backend.service.conversation.ConversationService;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final SecurityUtil securityUtil;

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationResponse>> create(
            @Valid @RequestBody CreateConversationRequest request,
            HttpServletRequest httpRequest) {
        UUID ownerId = securityUtil.getCurrentUserIdOrNull();
        String ipAddress = extractClientIp(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(conversationService.create(request, ownerId, ipAddress)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ConversationResponse>>> getByUser(@RequestParam UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(conversationService.getByUser(userId)));
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

    /** Traffic arrives via the API Gateway, so prefer the forwarded client IP over the socket's. */
    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
