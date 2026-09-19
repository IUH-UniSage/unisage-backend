package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;

import lombok.Builder;

@Builder
public record TicketResponse(
    UUID id,
    UUID messageId,
    TicketType type,
    TicketStatus status,
    String title,
    String description,
    String resolution,
    UUID userId,
    String userName,
    String userEmail,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
