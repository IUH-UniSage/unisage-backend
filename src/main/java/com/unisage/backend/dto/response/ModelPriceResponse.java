package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.ModelPriceSource;

import lombok.Builder;

/** Prices are USD per 1M tokens; timestamps carry an explicit UTC offset. */
@Builder
public record ModelPriceResponse(
    UUID id,
    String provider,
    String modelName,
    BigDecimal inputPerMillion,
    BigDecimal outputPerMillion,
    BigDecimal cachedInputPerMillion,
    ModelPriceSource source,
    OffsetDateTime syncedAt,
    OffsetDateTime updatedAt,
    String updatedByEmail
) {}
