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
    /** Last change of the conversation (new message, rename, claim); pinning does not change it. */
    LocalDateTime updatedAt,
    /** When the owner pinned it in the sidebar, null when not pinned. */
    LocalDateTime pinnedAt,
    /** The share link this conversation was continued from (UNISAGE-107), or null. */
    UUID sourceShareId
) {}
