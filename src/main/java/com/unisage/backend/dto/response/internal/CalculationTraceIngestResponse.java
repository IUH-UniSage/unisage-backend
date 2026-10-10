package com.unisage.backend.dto.response.internal;

import java.util.List;
import java.util.UUID;

import lombok.Builder;

/** Result of {@code POST /internal/calculation-traces}: the items now stored for the message. */
@Builder
public record CalculationTraceIngestResponse(
    UUID messageId,
    List<String> itemIds
) {}
