package com.unisage.backend.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record ChatModelResponse(
    UUID id,
    String llmProvider,
    String llmModelName,
    String apiBaseUrl,
    Integer maxRpm,
    Integer priority,
    Integer errorCount,
    LocalDateTime lastErrorAt,
    Boolean isActive,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    String updatedBy
) {}
