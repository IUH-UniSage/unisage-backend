package com.unisage.backend.dto.response.internal;

import java.math.BigDecimal;
import java.util.List;

import lombok.Builder;

/** {@code GET /internal/model-pricing/snapshot}. Prices are USD per 1M tokens; {@code version} is
 * the shared registry version, bumped on every price change, so the agent reloads prices on the
 * same cycle as credentials and budgets. */
@Builder
public record InternalModelPricingSnapshotResponse(long version, List<PriceEntry> prices) {

    @Builder
    public record PriceEntry(
        String provider,
        String modelName,
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        BigDecimal cachedInputPerMillion
    ) {}
}
