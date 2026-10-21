package com.unisage.backend.service.dashboard;

import java.time.LocalDate;

import com.unisage.backend.dto.response.DashboardSummaryResponse;

public interface DashboardService {

    /** Aggregates every tile on the "Tổng quan hệ thống" admin dashboard for {@code period}. */
    DashboardSummaryResponse getSummary(DashboardPeriod period);

    /**
     * Same tiles for an admin-picked local date range ({@code fromDate}..{@code toDate} inclusive).
     * Rejects a reversed, future or longer-than-366-day range.
     */
    DashboardSummaryResponse getSummary(LocalDate fromDate, LocalDate toDate);
}
