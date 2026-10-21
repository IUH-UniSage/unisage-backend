package com.unisage.backend.service.dashboard;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.unisage.backend.dto.response.DashboardSummaryResponse.AnswerWindow;
import com.unisage.backend.dto.response.DashboardSummaryResponse.AskerWindow;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-105: the dashboard's period aggregates. Plain SQL because the percentile, bucket and
 * distinct-asker math has no derived-query equivalent. Every {@code from}/{@code to} is UTC
 * (that is how {@code messages.created_at} and {@code request_usage_logs.started_at} are stored);
 * bucketing converts to {@code zone} first so hours/days follow the app's calendar.
 */
@Component
@RequiredArgsConstructor
public class DashboardMetricsQueries {

    private final JdbcTemplate jdbcTemplate;

    /** Finished assistant answers - PENDING/STREAMING are still in flight and counted nowhere. */
    public AnswerWindow answers(LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'ERROR') AS failed,
                       COUNT(*) FILTER (WHERE status = 'COMPLETED'
                           AND citations IS NOT NULL AND jsonb_array_length(citations) > 0) AS cited,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY response_time_ms)
                           FILTER (WHERE status = 'COMPLETED') AS median_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY response_time_ms)
                           FILTER (WHERE status = 'COMPLETED') AS p95_ms
                FROM messages
                WHERE role = 'ASSISTANT' AND status IN ('COMPLETED', 'ERROR')
                  AND created_at >= ? AND created_at < ?
                """, (rs, rowNum) -> new AnswerWindow(
                        rs.getLong("total"),
                        rs.getLong("failed"),
                        rs.getLong("cited"),
                        roundedOrNull(rs.getObject("median_ms")),
                        roundedOrNull(rs.getObject("p95_ms"))),
                from, to);
    }

    /** Distinct people who sent at least one question - signed-in users and guest sessions apart. */
    public AskerWindow askers(LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT c.user_id) AS users, COUNT(DISTINCT c.guest_session_id) AS guests
                FROM messages m
                JOIN conversations c ON c.id = m.conversation_id
                WHERE m.role = 'USER' AND m.created_at >= ? AND m.created_at < ?
                """, (rs, rowNum) -> new AskerWindow(rs.getLong("users"), rs.getLong("guests")),
                from, to);
    }

    /** Answered/failed counts per local hour or day, keyed by the bucket's local start. */
    public Map<LocalDateTime, long[]> answerBuckets(LocalDateTime from, LocalDateTime to, String zone, boolean hourly) {
        Map<LocalDateTime, long[]> buckets = new HashMap<>();
        jdbcTemplate.query("""
                SELECT date_trunc(?, (created_at AT TIME ZONE 'UTC') AT TIME ZONE ?) AS bucket,
                       COUNT(*) FILTER (WHERE status = 'COMPLETED') AS answered,
                       COUNT(*) FILTER (WHERE status = 'ERROR') AS failed
                FROM messages
                WHERE role = 'ASSISTANT' AND status IN ('COMPLETED', 'ERROR')
                  AND created_at >= ? AND created_at < ?
                GROUP BY bucket
                """, rs -> {
                    buckets.put(rs.getTimestamp("bucket").toLocalDateTime(),
                            new long[] { rs.getLong("answered"), rs.getLong("failed") });
                }, truncUnit(hourly), zone, from, to);
        return buckets;
    }

    /** Priced AI spend per local hour or day, keyed by the bucket's local start. */
    public Map<LocalDateTime, BigDecimal> costBuckets(LocalDateTime from, LocalDateTime to, String zone, boolean hourly) {
        Map<LocalDateTime, BigDecimal> buckets = new HashMap<>();
        jdbcTemplate.query("""
                SELECT date_trunc(?, (started_at AT TIME ZONE 'UTC') AT TIME ZONE ?) AS bucket,
                       SUM(total_cost_usd) AS cost
                FROM request_usage_logs
                WHERE started_at >= ? AND started_at < ?
                GROUP BY bucket
                """, rs -> {
                    buckets.put(rs.getTimestamp("bucket").toLocalDateTime(), rs.getBigDecimal("cost"));
                }, truncUnit(hourly), zone, from, to);
        return buckets;
    }

    public CostTotals costTotals(LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(total_cost_usd), 0) AS priced,
                       COALESCE(SUM(estimated_unpriced_cost_usd), 0) AS unpriced,
                       COUNT(*) AS request_count
                FROM request_usage_logs
                WHERE started_at >= ? AND started_at < ?
                """, (rs, rowNum) -> new CostTotals(
                        rs.getBigDecimal("priced"), rs.getBigDecimal("unpriced"), rs.getLong("request_count")),
                from, to);
    }

    /** Priced spend per {@code UsagePurpose}, most expensive first. */
    public List<PurposeCostRow> costByPurpose(LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                SELECT purpose, SUM(total_cost_usd) AS cost, COUNT(*) AS request_count
                FROM request_usage_logs
                WHERE started_at >= ? AND started_at < ?
                GROUP BY purpose
                ORDER BY cost DESC
                """, (rs, rowNum) -> new PurposeCostRow(
                        rs.getString("purpose"), rs.getBigDecimal("cost"), rs.getLong("request_count")),
                from, to);
    }

    /**
     * Spend per (provider, model) over every line attempt - failed attempts included, since a
     * provider bills them too. {@code requestCount} counts distinct business requests.
     */
    public List<ModelCostRow> costByModel(LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                SELECT l.provider, l.model_name,
                       COUNT(DISTINCT l.usage_log_id) AS request_count,
                       COUNT(*) AS call_count,
                       COUNT(*) FILTER (WHERE l.status = 'ERROR') AS error_count,
                       SUM(l.input_tokens) AS input_tokens,
                       SUM(l.output_tokens) AS output_tokens,
                       SUM(CASE WHEN l.cost_status = 'PRICED' THEN l.cost_usd ELSE 0 END) AS priced,
                       SUM(CASE WHEN l.cost_status = 'UNPRICED' THEN l.estimated_cost_usd ELSE 0 END) AS unpriced,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY l.latency_ms)
                           FILTER (WHERE l.status = 'SUCCESS') AS median_latency_ms
                FROM request_usage_lines l
                JOIN request_usage_logs g ON g.id = l.usage_log_id
                WHERE g.started_at >= ? AND g.started_at < ?
                GROUP BY l.provider, l.model_name
                ORDER BY priced DESC, unpriced DESC
                """, (rs, rowNum) -> new ModelCostRow(
                        rs.getString("provider"),
                        rs.getString("model_name"),
                        rs.getLong("request_count"),
                        rs.getLong("call_count"),
                        rs.getLong("error_count"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getBigDecimal("priced"),
                        rs.getBigDecimal("unpriced"),
                        roundedOrNull(rs.getObject("median_latency_ms"))),
                from, to);
    }

    /** Priced spend per (provider, model) - only what the comparison column needs. */
    public Map<String, BigDecimal> pricedCostByModel(LocalDateTime from, LocalDateTime to) {
        Map<String, BigDecimal> costs = new HashMap<>();
        jdbcTemplate.query("""
                SELECT l.provider, l.model_name,
                       SUM(CASE WHEN l.cost_status = 'PRICED' THEN l.cost_usd ELSE 0 END) AS priced
                FROM request_usage_lines l
                JOIN request_usage_logs g ON g.id = l.usage_log_id
                WHERE g.started_at >= ? AND g.started_at < ?
                GROUP BY l.provider, l.model_name
                """, rs -> {
                    costs.put(modelKey(rs.getString("provider"), rs.getString("model_name")),
                            rs.getBigDecimal("priced"));
                }, from, to);
        return costs;
    }

    public static String modelKey(String provider, String modelName) {
        return provider + "/" + modelName;
    }

    private static String truncUnit(boolean hourly) {
        return hourly ? "hour" : "day";
    }

    private static Long roundedOrNull(Object value) {
        return value == null ? null : Math.round(((Number) value).doubleValue());
    }

    public record CostTotals(BigDecimal pricedUsd, BigDecimal unpricedUsd, long requestCount) {
    }

    public record ModelCostRow(
            String provider,
            String modelName,
            long requestCount,
            long callCount,
            long errorCount,
            long inputTokens,
            long outputTokens,
            BigDecimal pricedUsd,
            BigDecimal unpricedEstimateUsd,
            Long medianLatencyMs) {
    }

    public record PurposeCostRow(String purpose, BigDecimal costUsd, long requestCount) {
    }
}
