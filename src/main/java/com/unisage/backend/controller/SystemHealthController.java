package com.unisage.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.HealthCheckResponse;
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
}
