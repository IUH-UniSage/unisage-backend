package com.unisage.backend.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/** USD per 1M tokens. {@code provider}/{@code modelName} are only read on create - a price row
 * never moves to another model. */
public record ModelPriceRequest(
    String provider,
    String modelName,
    @NotNull(message = "inputPerMillion không được để trống")
    BigDecimal inputPerMillion,
    BigDecimal outputPerMillion,
    BigDecimal cachedInputPerMillion
) {}
