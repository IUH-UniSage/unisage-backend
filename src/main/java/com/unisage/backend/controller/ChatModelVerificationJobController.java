package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.ChatModelVerificationJobResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.service.chatmodelverificationjob.ChatModelVerificationJobService;

import lombok.RequiredArgsConstructor;

/**
 * Admin "Jobs" tab — read-only, no realtime push (SA reloads the page to see new state, per the
 * feature request). Nested under {@code /chat-models/**} on purpose so the existing
 * {@code CHAT_MODEL_READ} permission (path pattern {@code /chat-models/**}, method GET, see
 * {@code DataInitializer}) already covers both endpoints below without a new permission row.
 */
@RestController
@RequestMapping("/chat-models/verifications")
@RequiredArgsConstructor
public class ChatModelVerificationJobController {

    private final ChatModelVerificationJobService chatModelVerificationJobService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<ChatModelVerificationJobResponse>>>> getAll(
            @RequestParam(required = false) UUID chatModelId,
            @RequestParam(required = false) ChatModelVerificationStatus status,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                chatModelVerificationJobService.getAll(chatModelId, status, pageable)));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<ChatModelVerificationJobResponse>> getById(
            @PathVariable UUID jobId) {
        return ResponseEntity.ok(ApiResponse.success(chatModelVerificationJobService.getById(jobId)));
    }
}
