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
            @RequestParam(required = false) UUID userId,
            @Valid @RequestBody CreateConversationRequest request) {
        UUID ownerId = userId != null ? userId : securityUtil.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(conversationService.create(request, ownerId)));
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
}
