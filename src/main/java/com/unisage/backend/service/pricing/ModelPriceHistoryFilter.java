package com.unisage.backend.service.pricing;

import java.time.LocalDateTime;

import com.unisage.backend.entity.enums.ModelPriceChangeType;

/** All optional. {@code model} is an exact name, {@code query} a case-insensitive substring;
 * {@code from}/{@code to} are UTC, {@code to} exclusive. */
public record ModelPriceHistoryFilter(
    String provider,
    String model,
    String query,
    ModelPriceChangeType changeType,
    LocalDateTime from,
    LocalDateTime to
) {}
