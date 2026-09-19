package com.unisage.backend.predefined;

import java.util.List;

public final class PredefinedPublicPaths {
    private PredefinedPublicPaths() {}

    /** {@code method} is an HTTP method name, or "*" to match any method. */
    public record PublicPath(String method, String pattern) {}

    /**
     * Reachable by any authenticated, active user with a role — no {@code Permission} row needed.
     * For self-service endpoints whose subject is always the caller (id resolved from the JWT,
     * never from the request), so gating them per role would only lock out custom roles.
     */
    public static final List<PublicPath> AUTHENTICATED_ONLY_PATHS = List.of(
            new PublicPath("GET", "/users/me"),
            new PublicPath("PATCH", "/users/me/password"),
            // Support tickets: a user files and reads only their own (id from the JWT). Admin
            // list/detail/update stay on TICKET_* Permission rows.
            new PublicPath("POST", "/tickets"),
            new PublicPath("GET", "/tickets/my"),
            new PublicPath("GET", "/tickets/my/*")
    );

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
            new PublicPath("GET", "/conversations/guest"),
            new PublicPath("POST", "/messages"),
            new PublicPath("GET", "/messages/conversation/**"),
            // unisage-agent (Python) streams the answer and calls back here to persist/finalize
            // the assistant Message row. No end-user JWT context on that call — it's public from
            // Spring Security's point of view, but InternalSecretFilter (see security package)
            // rejects it outright unless it carries a valid X-Internal-Secret header, so this is
            // gated by that shared secret rather than by user ownership.
            new PublicPath("PATCH", "/messages/*")
    );
}
