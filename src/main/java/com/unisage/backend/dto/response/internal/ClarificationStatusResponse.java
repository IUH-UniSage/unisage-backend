package com.unisage.backend.dto.response.internal;

import java.util.UUID;

import lombok.Builder;

/** Result of {@code PATCH /internal/messages/{id}/clarification}. */
@Builder
public record ClarificationStatusResponse(
    UUID messageId,
    UUID conversationId,
    String status
) {}
