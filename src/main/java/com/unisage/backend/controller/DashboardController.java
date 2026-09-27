package com.unisage.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.DashboardSummaryResponse;
import com.unisage.backend.service.dashboard.DashboardService;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-72: backs the "Tổng quan hệ thống" admin dashboard (previously static mock data in
 * unisage-web's AdminOverviewPage) with real counts. Gated by {@code DASHBOARD_READ}.
 */
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> getSummary() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getSummary()));
    }
}
