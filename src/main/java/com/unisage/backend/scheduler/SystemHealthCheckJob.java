package com.unisage.backend.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unisage.backend.service.health.SystemHealthCheckService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * UNISAGE-62: periodically runs the same health-check logic {@code GET /admin/health} runs live
 * (see {@link SystemHealthCheckService#runAndPersist()}) and persists one
 * {@code SystemHealthCheck} row per run, so past downtime is queryable via
 * {@code GET /admin/health/history} instead of only ever seeing a live snapshot. Interval
 * configurable via {@code app.health-check.history.cron} (default: every 5 minutes).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SystemHealthCheckJob {

    private final SystemHealthCheckService systemHealthCheckService;

    @Scheduled(cron = "${app.health-check.history.cron:0 */5 * * * *}")
    public void run() {
        systemHealthCheckService.runAndPersist();
        log.debug("System health-check snapshot persisted.");
    }
}
