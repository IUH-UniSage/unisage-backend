package com.unisage.backend.dto.response;

import java.time.LocalDate;
import java.util.List;

import lombok.Builder;

/**
 * UNISAGE-72: the "Tổng quan hệ thống" dashboard's single aggregate payload - one call replaces
 * the static mock data that {@code AdminOverviewPage} (unisage-web) used to render.
 */
@Builder
public record DashboardSummaryResponse(
        UserStats users,
        AiAnswerStats aiAnswers,
        DocumentStats documents,
        TicketStats tickets,
        WeeklyActivity weeklyActivity,
        HealthCheckResponse health) {

    @Builder
    public record UserStats(long activeTotal, long activeToday) {
    }

    /** {@code citedPercentage} is null when no assistant messages were sent today (avoid a 0/0 divide). */
    @Builder
    public record AiAnswerStats(long today, Double citedPercentage) {
    }

    @Builder
    public record DocumentStats(long published, long departments) {
    }

    /**
     * Only {@code open} is exposed - support-request tickets have no assignee field
     * (see {@code Ticket}'s own javadoc), so an "unassigned" count isn't available.
     */
    @Builder
    public record TicketStats(long open) {
    }

    @Builder
    public record DailyActivity(LocalDate date, long questions) {
    }

    /**
     * Last 7 days of assistant-answer volume. Source-open counts and helpful-rating percentages
     * from the original mock UI have no backing schema anywhere in the system and are
     * intentionally omitted rather than faked.
     */
    @Builder
    public record WeeklyActivity(List<DailyActivity> daily, long totalQuestions) {
    }
}
