package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

@Builder
public record ConversationResponse(
    UUID id,
    UUID userId,
    UUID departmentId,
    String title,
    String summary,
    Integer messageCount,
    LocalDateTime createdAt
) {}
