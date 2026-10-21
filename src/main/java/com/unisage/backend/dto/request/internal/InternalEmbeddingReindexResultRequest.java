package com.unisage.backend.dto.request.internal;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * {@code POST /internal/embedding-reindex/{runId}/result}. {@code succeeded = true} asks Java to cut
 * over to the target collection; false ends the run FAILED with {@code errorMessage}.
 */
public record InternalEmbeddingReindexResultRequest(
    @NotNull(message = "leaseToken không được để trống")
    UUID leaseToken,

    @NotNull(message = "succeeded không được để trống")
    Boolean succeeded,

    @PositiveOrZero(message = "totalPoints không được âm")
    Integer totalPoints,

    @NotNull(message = "processedPoints không được để trống")
    @PositiveOrZero(message = "processedPoints không được âm")
    Integer processedPoints,

    @NotNull(message = "failedPoints không được để trống")
    @PositiveOrZero(message = "failedPoints không được âm")
    Integer failedPoints,

    String errorMessage
) {}
