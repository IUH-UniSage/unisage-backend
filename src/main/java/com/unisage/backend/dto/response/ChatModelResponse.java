package com.unisage.backend.dto.response;

import com.unisage.backend.entity.enums.ChatModelSourceType;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record ChatModelResponse(
    UUID id,
    ChatModelSourceType sourceType,
    String llmProvider,
    String llmModelName,
    String modelSourceRef,
    Boolean hasApiKey,
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
