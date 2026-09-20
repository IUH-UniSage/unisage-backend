package com.unisage.backend.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-62: reachability probe for {@code unisage-gateway}. That service has no
 * actuator/health endpoint of its own (out of scope — separate repo), so this does a plain HTTP
 * GET against its base URL: any response, including a 404, proves the process is up and
 * accepting connections, while a timeout/connection-refused means it is unreachable.
 */
@Component
@RequiredArgsConstructor
public class GatewayHealthIndicator implements HealthIndicator {

    private final RestTemplate healthCheckRestTemplate;

    @Value("${app.health-check.gateway-url}")
    private String gatewayUrl;

    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = healthCheckRestTemplate.getForEntity(gatewayUrl, String.class);
            return up(response.getStatusCode(), System.currentTimeMillis() - start);
        } catch (HttpStatusCodeException e) {
            // Any HTTP status back (including 4xx/5xx) still proves the gateway is reachable.
            return up(e.getStatusCode(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("url", gatewayUrl)
                    .withDetail("responseTimeMs", System.currentTimeMillis() - start)
                    .build();
        }
    }

    private Health up(HttpStatusCode statusCode, long elapsedMs) {
        return Health.up()
                .withDetail("url", gatewayUrl)
                .withDetail("statusCode", statusCode.value())
                .withDetail("responseTimeMs", elapsedMs)
                .build();
    }
}
