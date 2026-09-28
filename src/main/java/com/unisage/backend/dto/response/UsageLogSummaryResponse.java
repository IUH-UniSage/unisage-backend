package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.util.List;

import lombok.Builder;

@Builder
public record UsageLogSummaryResponse(String groupBy, List<Bucket> buckets) {

    @Builder
    public record Bucket(
        /** Group key - a purpose/provider/model name, a user id, or an ISO day string, depending on groupBy. */
        String key,
        BigDecimal pricedCostUsd,
        BigDecimal estimatedUnpricedCostUsd,
        long requestCount,
        long totalInputTokens,
        long totalOutputTokens
    ) {}
}
