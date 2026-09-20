package com.unisage.backend.dto.response;

import java.util.Map;

import lombok.Builder;

/**
 * One entry of {@link HealthCheckResponse#components()} — mirrors the shape of a single node in
 * Spring Boot Actuator's {@code HealthComponent} tree (see {@code HealthEndpoint#health()}), so
 * the FE contract stays compatible with a future raw Actuator response.
 */
@Builder
public record ComponentHealthResponse(
        String status,
        Map<String, Object> details) {
}
