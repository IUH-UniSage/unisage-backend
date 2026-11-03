package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

@Builder
public record ConversationResponse(
    UUID id,
    UUID userId,
    String title,
    LocalDateTime createdAt,
    /** The share link this conversation was continued from (UNISAGE-107), or null. */
    UUID sourceShareId
) {}
