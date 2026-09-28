package com.unisage.backend.service.usagelog;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.UsagePurpose;

import lombok.RequiredArgsConstructor;

/**
 * "Spend so far this period" for one {@code Budget} scope - matches the Redis {@code committed}
 * definition in plan.md "Budget semantics": PRICED lines count {@code costUsd}, UNPRICED lines
 * count {@code estimatedCostUsd}, FREE lines are excluded. SYSTEM/PURPOSE read the parent
 * (denormalized per-request totals); PROVIDER must read lines directly, since a provider is a
 * per-line snapshot - a single request can span more than one provider after a failover.
 */
@Service
@RequiredArgsConstructor
public class UsagePeriodTotalsService {

    private static final String SYSTEM_OR_PURPOSE_SQL = """
            SELECT COALESCE(SUM(total_cost_usd + estimated_unpriced_cost_usd), 0)
            FROM request_usage_logs
            WHERE started_at >= ? AND started_at < ?
            """;

    private static final String SYSTEM_OR_PURPOSE_BY_PURPOSE_SQL = SYSTEM_OR_PURPOSE_SQL + " AND purpose = ?";

    private static final String PROVIDER_SQL = """
            SELECT COALESCE(SUM(CASE WHEN l.cost_status = 'PRICED' THEN l.cost_usd ELSE l.estimated_cost_usd END), 0)
            FROM request_usage_lines l
            JOIN request_usage_logs g ON g.id = l.usage_log_id
            WHERE lower(l.provider) = lower(?) AND l.cost_status <> 'FREE'
              AND g.started_at >= ? AND g.started_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public BigDecimal spentUsd(BudgetScope scope, String scopeProvider, UsagePurpose scopePurpose,
            LocalDateTime startUtc, LocalDateTime endUtc) {
        return switch (scope) {
            case SYSTEM -> jdbcTemplate.queryForObject(SYSTEM_OR_PURPOSE_SQL, BigDecimal.class, startUtc, endUtc);
            case PURPOSE -> jdbcTemplate.queryForObject(SYSTEM_OR_PURPOSE_BY_PURPOSE_SQL, BigDecimal.class,
                    startUtc, endUtc, scopePurpose.name());
            case PROVIDER -> jdbcTemplate.queryForObject(PROVIDER_SQL, BigDecimal.class,
                    scopeProvider, startUtc, endUtc);
        };
    }
}
