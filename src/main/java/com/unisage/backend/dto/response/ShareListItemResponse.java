package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

/** One row of {@code GET /shares} - the caller's live (non-revoked) links. */
@Builder
public record ShareListItemResponse(
    UUID id,
    String token,
    String title,
    UUID conversationId,
    int messageCount,
    LocalDateTime createdAt
) {}
