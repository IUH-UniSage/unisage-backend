package com.unisage.backend.service.usagelog;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.repository.BudgetRepository;

import lombok.RequiredArgsConstructor;

/**
 * Backs {@code GET /internal/usage-logs/period-totals} - plan.md "Đối soát committed". Every
 * amount is rounded to micro-USD **per line, before summing** (never sum-then-round), matching the
 * Redis {@code committed} counter unit exactly.
 */
@Service
@RequiredArgsConstructor
public class InternalPeriodTotalsService {

    private static final String SYSTEM_SQL = """
            SELECT COALESCE(SUM(
                CASE l.cost_status
                    WHEN 'PRICED' THEN ROUND(l.cost_usd * 1000000)
                    WHEN 'UNPRICED' THEN ROUND(l.estimated_cost_usd * 1000000)
                    ELSE 0
                END
            ), 0)::bigint
            FROM request_usage_lines l
            JOIN request_usage_logs g ON g.id = l.usage_log_id
            WHERE g.started_at >= ? AND g.started_at < ?
            """;

    private static final String PURPOSE_SQL = SYSTEM_SQL + " AND g.purpose = ?";

    private static final String PROVIDER_SQL = SYSTEM_SQL + " AND lower(l.provider) = lower(?)";

    private final JdbcTemplate jdbcTemplate;
    private final BudgetRepository budgetRepository;

    public Map<String, Long> periodTotalsMicroUsd(LocalDateTime startUtc, LocalDateTime endUtc) {
        Map<String, Long> totals = new LinkedHashMap<>();

        totals.put("SYSTEM", jdbcTemplate.queryForObject(SYSTEM_SQL, Long.class, startUtc, endUtc));

        for (UsagePurpose purpose : UsagePurpose.values()) {
            long value = jdbcTemplate.queryForObject(PURPOSE_SQL, Long.class, startUtc, endUtc, purpose.name());
            totals.put("PURPOSE:" + purpose.name(), value);
        }

        for (String provider : budgetRepository.findDistinctEnabledScopeProviders(BudgetScope.PROVIDER)) {
            long value = jdbcTemplate.queryForObject(PROVIDER_SQL, Long.class, startUtc, endUtc, provider);
            totals.put("PROVIDER:" + provider.toLowerCase(), value);
        }

        return totals;
    }
}
