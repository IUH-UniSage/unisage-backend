package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.CreateShareRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.ContinueShareResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.ShareListItemResponse;
import com.unisage.backend.dto.response.ShareResponse;
import com.unisage.backend.dto.response.SharedConversationResponse;
import com.unisage.backend.service.conversation.ConversationShareService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Conversation share links (UNISAGE-107 §5.2). Every route is in
 * {@code PredefinedPublicPaths.AUTHENTICATED_ONLY_PATHS}: any signed-in user, no Permission row;
 * who may do what is decided in {@link ConversationShareService}.
 */
@RestController
@RequestMapping("/shares")
@RequiredArgsConstructor
public class ConversationShareController {

    private final ConversationShareService shareService;

    @PostMapping
    public ResponseEntity<ApiResponse<ShareResponse>> create(@Valid @RequestBody CreateShareRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(shareService.create(request.conversationId())));
    }

    /** {@code ?page=1&limit=10} (1-based, like every other paged list here); newest first. */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<ShareListItemResponse>>>> listMine(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(shareService.listMine(pageable)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable UUID id) {
        shareService.revoke(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Revokes all of the caller's links; {@code data} is how many were revoked. */
    @DeleteMapping
    public ResponseEntity<ApiResponse<Integer>> revokeAll() {
        return ResponseEntity.ok(ApiResponse.success(shareService.revokeAll()));
    }

    @GetMapping("/view/{token}")
    public ResponseEntity<ApiResponse<SharedConversationResponse>> view(@PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.success(shareService.view(token)));
    }

    @PostMapping("/view/{token}/continue")
    public ResponseEntity<ApiResponse<ContinueShareResponse>> continueFromShare(@PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.success(shareService.continueFromShare(token)));
    }
}
