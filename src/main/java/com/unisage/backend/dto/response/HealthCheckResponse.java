package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

import lombok.Builder;

/**
 * UNISAGE-62: Actuator-shaped aggregate health-check result — same status vocabulary
 * (UP/DOWN/DEGRADED/OUT_OF_SERVICE) and a {@code components} map keyed by dependency name,
 * mirroring {@code /actuator/health}'s structure. Used both for the live
 * {@code GET /admin/health} response and, re-hydrated from {@code SystemHealthCheck.componentsJson},
 * for each row of {@code GET /admin/health/history} — one consistent contract either way.
 */
@Builder
public record HealthCheckResponse(
        String overallStatus,
        LocalDateTime checkedAt,
        Map<String, ComponentHealthResponse> components) {
}
