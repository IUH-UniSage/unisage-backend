package com.unisage.backend.dto.request.internal;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** {@code POST /internal/embedding-reindex/{runId}/progress} — also renews the lease. */
public record InternalEmbeddingReindexProgressRequest(
    @NotNull(message = "leaseToken không được để trống")
    UUID leaseToken,

    @PositiveOrZero(message = "totalPoints không được âm")
    Integer totalPoints,

    @NotNull(message = "processedPoints không được để trống")
    @PositiveOrZero(message = "processedPoints không được âm")
    Integer processedPoints,

    @NotNull(message = "failedPoints không được để trống")
    @PositiveOrZero(message = "failedPoints không được âm")
    Integer failedPoints
) {}
