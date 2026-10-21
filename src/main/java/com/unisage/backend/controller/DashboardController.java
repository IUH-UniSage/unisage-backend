package com.unisage.backend.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.DashboardSummaryResponse;
import com.unisage.backend.service.dashboard.DashboardPeriod;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.service.dashboard.DashboardService;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-72: backs the "Tổng quan hệ thống" admin dashboard (previously static mock data in
 * unisage-web's AdminOverviewPage) with real counts. Gated by {@code DASHBOARD_READ}.
 * UNISAGE-105 adds the {@code period} switcher (default today) and custom date ranges.
 */
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Either a preset {@code period}, or {@code period=CUSTOM} with {@code from}/{@code to} local
     * dates (ISO, inclusive).
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> getSummary(
            @RequestParam(defaultValue = "TODAY") DashboardPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (period != DashboardPeriod.CUSTOM) {
            return ResponseEntity.ok(ApiResponse.success(dashboardService.getSummary(period)));
        }
        if (from == null || to == null) {
            throw new AppException(ErrorCode.DASHBOARD_INVALID_RANGE);
        }
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getSummary(from, to)));
    }
}
