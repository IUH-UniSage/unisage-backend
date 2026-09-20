package com.unisage.backend.service.health;

import com.unisage.backend.dto.response.HealthCheckResponse;

public interface SystemHealthCheckService {

    /**
     * Runs every {@code HealthIndicator} live (via Actuator's {@code HealthEndpoint}) and
     * returns the aggregate. Does NOT write to the database — cheap/fast, safe to poll
     * frequently.
     */
    HealthCheckResponse checkNow();
}
