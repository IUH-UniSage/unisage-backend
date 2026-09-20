package com.unisage.backend.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.HealthCheckResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.service.health.SystemHealthCheckService;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-62: this app's own admin-facing health-check API — kept separate from Actuator's raw
 * {@code /actuator/health} (locked down, see {@code management.endpoints.web.exposure.include}
 * in application.properties) so the normal permission-based auth in this codebase applies
 * ({@code SYSTEM_HEALTH_READ}).
 */
@RestController
@RequestMapping("/admin/health")
@RequiredArgsConstructor
public class SystemHealthController {

    private final SystemHealthCheckService systemHealthCheckService;

    /**
     * Live aggregate status, computed on every call — not persisted. Cheap/fast by design (each
     * cross-service HealthIndicator has its own short timeout), meant to be polled frequently by
     * the FE dashboard.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<HealthCheckResponse>> getLiveHealth() {
        return ResponseEntity.ok(ApiResponse.success(systemHealthCheckService.checkNow()));
    }

    /**
     * Paginated history of past health-check runs (persisted every
     * {@code app.health-check.history.cron} by {@code SystemHealthCheckJob}), most recent first.
     * {@code from}/{@code to} are optional filters on {@code checkedAt}.
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PageResponse<List<HealthCheckResponse>>>> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(sort = "checkedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.success(systemHealthCheckService.getHistory(from, to, pageable)));
    }
}
