package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;

import lombok.Builder;

/**
 * Admin "Jobs" tab — list item and detail share this shape (todo.md "Xem danh sách jobs hiện
 * tại"). {@code candidateApiKeyEncrypted} is deliberately never exposed here, not even the
 * ciphertext — {@code hasCandidateApiKey} is all the UI needs.
 */
@Builder
public record ChatModelVerificationJobResponse(
        UUID id,
        UUID chatModelId,
        ChatModelPurpose modelPurpose,
        ChatModelVerificationStatus status,
        Integer candidateGeneration,
        Integer baseRevision,
        String candidateLlmProvider,
        String candidateLlmModelName,
        String candidateModelSourceRef,
        String candidateApiBaseUrl,
        boolean hasCandidateApiKey,
        Integer embeddingDimension,
        Integer attempt,
        Integer maxAttempts,
        LocalDateTime nextAttemptAt,
        LocalDateTime leaseUntil,
        String errorType,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
