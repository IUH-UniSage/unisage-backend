package com.unisage.backend.service.health;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.response.HealthCheckResponse;
import com.unisage.backend.dto.response.PageResponse;

public interface SystemHealthCheckService {

    /**
     * Runs every {@code HealthIndicator} live (via Actuator's {@code HealthEndpoint}) and
     * returns the aggregate. Does NOT write to the database — cheap/fast, safe to poll
     * frequently.
     */
    HealthCheckResponse checkNow();

    /**
     * Runs the same live check as {@link #checkNow()} and persists one
     * {@code SystemHealthCheck} row — used by the scheduled history job.
     */
    void runAndPersist();

    /**
     * Pages through past {@code SystemHealthCheck} rows, most recent first, optionally
     * filtered to a {@code [from, to]} window.
     */
    PageResponse<List<HealthCheckResponse>> getHistory(LocalDateTime from, LocalDateTime to, Pageable pageable);
}
