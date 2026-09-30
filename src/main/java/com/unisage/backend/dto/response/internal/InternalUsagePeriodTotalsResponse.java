package com.unisage.backend.dto.response.internal;

import java.util.Map;

import lombok.Builder;

/** {@code GET /internal/usage-logs/period-totals} - plan.md "Đối soát committed". Values are
 * micro-USD integers (rounded half-up per line, then summed - never sum-then-round), matching the
 * Redis {@code committed} counter's unit exactly so Python can {@code INCRBY committed (dbTotal -
 * C0)} without a unit conversion. Keys are {@code SYSTEM}, {@code PURPOSE:<name>} (always all 3
 * purposes), {@code PROVIDER:<name>} (one per provider with an enabled PROVIDER budget). */
@Builder
public record InternalUsagePeriodTotalsResponse(String period, String periodKey, Map<String, Long> totals) {
}
