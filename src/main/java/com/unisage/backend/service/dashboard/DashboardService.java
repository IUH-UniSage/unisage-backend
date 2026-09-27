package com.unisage.backend.service.dashboard;

import com.unisage.backend.dto.response.DashboardSummaryResponse;

public interface DashboardService {

    /** Aggregates every tile on the "Tổng quan hệ thống" admin dashboard into one payload. */
    DashboardSummaryResponse getSummary();
}
