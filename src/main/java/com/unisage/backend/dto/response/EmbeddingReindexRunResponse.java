package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.EmbeddingReindexStatus;

import lombok.Builder;

@Builder
public record EmbeddingReindexRunResponse(
    UUID id,
    EmbeddingReindexStatus status,
    String sourceCollection,
    String targetCollection,
    TargetModel targetModel,
    Integer totalPoints,
    Integer processedPoints,
    Integer failedPoints,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt
) {

    public record TargetModel(
        UUID id,
        String displayName,
        String llmProvider,
        String llmModelName,
        Integer embeddingDimension
    ) {}
}
