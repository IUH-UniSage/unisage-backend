package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
    String updatedByEmail,
    /** Chat-model providers that can call this model; see model-provider-support.yml. */
    List<String> supportedProviders,
    /** tested | paid | restricted | unsupported | inferred. */
    String supportStatus,
    String supportNote,
    /** LiteLLM's announced deprecation day, if any. */
    LocalDate deprecationDate,
    /** Past {@code deprecationDate}, or reported retired by the provider API. */
    boolean deprecated
) {}
