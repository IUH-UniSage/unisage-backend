package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.service.dashboard.DashboardPeriod;

import lombok.Builder;

/**
 * UNISAGE-72, reshaped by UNISAGE-105: the "Tổng quan hệ thống" dashboard's single aggregate
 * payload. Everything period-bound ({@link #answers}, {@link #askers}, {@link #activity},
 * {@link #cost}) follows {@link #period} and carries its previous-period counterpart; the rest
 * ({@link #attention}, {@link #documents}, {@link #health}) is the state right now.
 */
@Builder
public record DashboardSummaryResponse(
        PeriodInfo period,
        Attention attention,
        Comparison<AnswerWindow> answers,
        Comparison<AskerWindow> askers,
        List<ActivityBucket> activity,
        CostStats cost,
        DocumentStats documents,
        HealthCheckResponse health) {

    /** Local wall-clock bounds (app timezone). {@code granularity} is {@code HOUR} or {@code DAY}. */
    @Builder
    public record PeriodInfo(
            DashboardPeriod key,
            LocalDateTime from,
            LocalDateTime to,
            LocalDateTime previousFrom,
            LocalDateTime previousTo,
            String granularity) {
    }

    /**
     * Things an admin should act on now. {@code activeBudgetAlerts} is the in-app alerts nobody has
     * dismissed yet; {@code outdatedDocuments} had their file replaced but are not re-indexed.
     */
    @Builder
    public record Attention(
            long activeBudgetAlerts,
            long failedDocuments,
            long outdatedDocuments,
            long openTickets) {
    }

    public record Comparison<T>(T current, T previous) {
    }

    /**
     * {@code total} = {@code COMPLETED} + {@code ERROR} assistant messages. {@code cited} only counts
     * completed answers; response-time percentiles are null when nothing completed.
     */
    public record AnswerWindow(long total, long failed, long cited, Long medianResponseMs, Long p95ResponseMs) {
    }

    /** Distinct question askers - signed-in accounts and guest sessions. */
    public record AskerWindow(long users, long guests) {
    }

    /** {@code start} is the bucket's local start; {@code costUsd} is priced spend only. */
    @Builder
    public record ActivityBucket(LocalDateTime start, long answered, long failed, BigDecimal costUsd) {
    }

    /**
     * {@code totalUsd} is priced spend only - {@code unpricedEstimateUsd} (models with no price row)
     * is shown beside it, never folded in, matching the Cost Management page.
     */
    @Builder
    public record CostStats(
            BigDecimal totalUsd,
            BigDecimal previousTotalUsd,
            BigDecimal unpricedEstimateUsd,
            long requestCount,
            List<PurposeCost> byPurpose,
            List<ModelCost> byModel,
            List<BudgetUsage> budgets) {
    }

    /**
     * One provider/model's share of the period. {@code callCount}/{@code errorCount} are provider
     * calls (retries and failovers included); {@code previousCostUsd} is its priced spend in the
     * comparison window (zero when it was not used then).
     */
    @Builder
    public record ModelCost(
            String provider,
            String modelName,
            long requestCount,
            long callCount,
            long errorCount,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            BigDecimal previousCostUsd,
            BigDecimal unpricedEstimateUsd,
            Long medianLatencyMs) {
    }

    public record PurposeCost(UsagePurpose purpose, BigDecimal costUsd, long requestCount) {
    }

    /** An enabled budget's own current period (not the dashboard period). */
    @Builder
    public record BudgetUsage(
            String id,
            BudgetScope scope,
            String scopeProvider,
            UsagePurpose scopePurpose,
            BudgetPeriod period,
            BigDecimal spentUsd,
            BigDecimal limitUsd,
            Double spentPercent) {
    }

    @Builder
    public record DocumentStats(long published, long departments) {
    }
}
