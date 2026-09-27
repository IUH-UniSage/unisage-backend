package com.unisage.backend.dto.response;

import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * SA-facing summary of the most recent verification job for a {@code ChatModel} — never exposes
 * the candidate credential values (plan.md "Internal API contract" DTOs are internal-only; this
 * is the SA-safe subset).
 */
@Builder
public record ChatModelVerificationSummary(
    UUID id,
    ChatModelVerificationStatus status,
    Integer attempt,
    String errorType,
    String errorCode,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime finishedAt
) {}
