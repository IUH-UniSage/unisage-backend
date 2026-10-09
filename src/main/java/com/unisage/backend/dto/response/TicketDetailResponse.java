package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;

import lombok.Builder;

/** {@link TicketResponse} plus the conversation context, resolved at read time (no snapshot stored). */
@Builder
public record TicketDetailResponse(
    UUID id,
    UUID messageId,
    /** {@code T1}..{@code T3} for an {@code AI_CALCULATION_WRONG} ticket, null otherwise. */
    String calculationItemId,
    UUID conversationId,
    TicketType type,
    TicketStatus status,
    String title,
    String description,
    String resolution,
    UUID userId,
    String userName,
    String userEmail,
    /** The user message that immediately preceded the reported AI answer; null if none is found. */
    String questionContent,
    /** The reported AI answer. */
    String messageContent,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
