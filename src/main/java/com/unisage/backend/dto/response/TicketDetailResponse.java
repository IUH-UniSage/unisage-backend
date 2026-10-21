package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    /** Sources the answer cites ({@code [n]} markers), as stored on the message. Staff view only. */
    List<Map<String, Object>> citations,
    /** Id on every agent log line of the turn that produced the answer. Staff view only; null if none. */
    String traceId,
    /** Every chunk given to the LLM for the answer, cited or not. Staff view only; null if no trace. */
    List<Map<String, Object>> contextChunks,
    /** Every web page given to the LLM (content or link only). Staff view only; null if no trace. */
    List<Map<String, Object>> webSources,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
