package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.ModelPriceChangeType;

import lombok.Builder;

/** Prices are USD per 1M tokens; {@code changedByEmail} is null for a sync. */
@Builder
public record ModelPriceChangeResponse(
    UUID id,
    String provider,
    String modelName,
    ModelPriceChangeType changeType,
    BigDecimal oldInputPerMillion,
    BigDecimal newInputPerMillion,
    BigDecimal oldOutputPerMillion,
    BigDecimal newOutputPerMillion,
    BigDecimal oldCachedInputPerMillion,
    BigDecimal newCachedInputPerMillion,
    String changedByEmail,
    OffsetDateTime changedAt
) {}
