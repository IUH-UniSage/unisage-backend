package com.unisage.backend.predefined;

import java.util.List;

public final class PredefinedPublicPaths {
    private PredefinedPublicPaths() {}

    /** {@code method} is an HTTP method name, or "*" to match any method. */
    public record PublicPath(String method, String pattern) {}

    public static final List<PublicPath> PUBLIC_PATHS = List.of(
            // Auth
            new PublicPath("*", "/auth/**"),
            // AI Agent
            new PublicPath("*", "/ai/**"),
            // Swagger / OpenAPI
            new PublicPath("*", "/swagger-ui/**"),
            new PublicPath("*", "/v3/api-docs/**"),
            new PublicPath("*", "/swagger-resources/**"),
            new PublicPath("*", "/webjars/**"),
            // Actuator health
            new PublicPath("*", "/actuator/health"),
            // Guest chat — anonymous conversation/message creation & history read.
            // Everything else on these resources (list-by-user, delete, claim) stays RBAC-gated.
            new PublicPath("POST", "/conversations"),
            new PublicPath("POST", "/messages"),
            new PublicPath("GET", "/messages/conversation/**")
    );
}
