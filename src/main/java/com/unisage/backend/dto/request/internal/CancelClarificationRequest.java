package com.unisage.backend.dto.request.internal;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /internal/messages/{id}/clarification} body. unisage-agent only ever sends
 * {@code status = "cancelled"}; any other target status is answered with 409, not 400, since it is
 * a disallowed transition rather than a malformed request.
 */
public record CancelClarificationRequest(
    @NotNull(message = "conversationId không được để trống")
    UUID conversationId,

    @NotBlank(message = "status không được để trống")
    String status
) {}
