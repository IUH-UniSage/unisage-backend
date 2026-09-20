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
            String status = dataObj instanceof Map<?, ?> data ? String.valueOf(data.get("status")) : null;

            if ("healthy".equalsIgnoreCase(status)) {
                return Health.up()
                        .withDetail("url", url)
                        .withDetail("status", status)
                        .withDetail("responseTimeMs", elapsedMs)
                        .build();
            }
            return Health.down()
                    .withDetail("url", url)
                    .withDetail("status", status)
                    .withDetail("responseTimeMs", elapsedMs)
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("url", url)
                    .withDetail("responseTimeMs", System.currentTimeMillis() - start)
                    .build();
        }
    }
}
