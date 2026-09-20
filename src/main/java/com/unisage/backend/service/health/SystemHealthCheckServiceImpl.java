package com.unisage.backend.service.health;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.response.ComponentHealthResponse;
import com.unisage.backend.dto.response.HealthCheckResponse;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-62. The "run every indicator and build the aggregate" logic lives here in exactly one
 * place ({@link #checkNow()}), built on Actuator's {@code HealthEndpoint} (which aggregates the
 * auto-configured {@code DataSourceHealthIndicator} plus the custom gateway/agent/minio
 * indicators) rather than a hand-rolled poller.
 */
@Service
@RequiredArgsConstructor
public class SystemHealthCheckServiceImpl implements SystemHealthCheckService {

    private final HealthEndpoint healthEndpoint;

    @Override
    public HealthCheckResponse checkNow() {
        HealthComponent root = healthEndpoint.health();
        return HealthCheckResponse.builder()
                .overallStatus(root.getStatus().getCode())
                .checkedAt(LocalDateTime.now())
                .components(flatten(root))
                .build();
    }

    private Map<String, ComponentHealthResponse> flatten(HealthComponent root) {
        Map<String, ComponentHealthResponse> result = new LinkedHashMap<>();
        if (root instanceof CompositeHealth composite && composite.getComponents() != null) {
            composite.getComponents().forEach((name, component) -> result.put(name, toComponentResponse(component)));
        } else {
            result.put("system", toComponentResponse(root));
        }
        return result;
    }

    private ComponentHealthResponse toComponentResponse(HealthComponent component) {
        Map<String, Object> details = component instanceof Health health ? health.getDetails() : Map.of();
        return ComponentHealthResponse.builder()
                .status(component.getStatus().getCode())
                .details(details)
                .build();
    }
}
