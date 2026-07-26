package com.unisage.backend.predefined;

import java.util.List;

public final class PredefinedPublicPaths {
    private PredefinedPublicPaths() {}

    public static final List<String> PUBLIC_PATHS = List.of(
            // Auth
            "/auth/**",
            // AI Agent
            "/ai/**",
            // Swagger / OpenAPI
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/webjars/**",
            // Actuator health
            "/actuator/health"
    );
}
