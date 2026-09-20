package com.unisage.backend.health;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-62: reachability + liveness probe for {@code unisage-agent} (Python/RAG service).
 * Unlike the gateway, this service already exposes a real health endpoint
 * ({@code GET /api/v1/health}, see {@code unisage-agent/app/api/v1/health.py}) returning
 * {@code {"code":..., "data": {"status": "healthy", ...}}} — UP only when
 * {@code data.status == "healthy"}; anything else (different status value, malformed body,
 * timeout, connection error) is DOWN.
 */
@Component
@RequiredArgsConstructor
public class AgentHealthIndicator implements HealthIndicator {

    private static final String HEALTH_PATH = "/api/v1/health";

    private final RestTemplate healthCheckRestTemplate;

    @Value("${app.health-check.agent-url}")
    private String agentUrl;

    @SuppressWarnings("unchecked")
    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        String url = agentUrl + HEALTH_PATH;
        try {
            Map<String, Object> body = healthCheckRestTemplate.getForObject(url, Map.class);
            long elapsedMs = System.currentTimeMillis() - start;
            Object dataObj = body != null ? body.get("data") : null;
            Map<?, ?> data = dataObj instanceof Map<?, ?> m ? m : Map.of();
            String status = String.valueOf(data.get("status"));
            // unisage-agent's own /health checks ITS OWN dependencies (Postgres, Redis, Qdrant -
            // see unisage-agent/app/api/v1/health.py) and reports them under "components". Forward
            // that breakdown as-is so the dashboard can show which specific one is down instead of
            // just "Trợ lý AI: down" with no further explanation.
            Object components = data.get("components");

            Health.Builder builder = "healthy".equalsIgnoreCase(status) ? Health.up() : Health.down();
            builder.withDetail("url", url)
                    .withDetail("status", status)
                    .withDetail("responseTimeMs", elapsedMs);
            if (components != null) {
                builder.withDetail("components", components);
            }
            return builder.build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("url", url)
                    .withDetail("responseTimeMs", System.currentTimeMillis() - start)
                    .build();
        }
    }
}
