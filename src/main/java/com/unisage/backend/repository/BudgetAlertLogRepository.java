package com.unisage.backend.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.BudgetAlertLog;
import com.unisage.backend.entity.enums.AlertChannel;

public interface BudgetAlertLogRepository extends JpaRepository<BudgetAlertLog, UUID> {

    Page<BudgetAlertLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<BudgetAlertLog> findByChannelAndDismissedAtIsNull(AlertChannel channel);

    /**
     * Atomic claim: inserts a PENDING row for {@code dedupeKey}, or does nothing if a row with
     * that key already exists - the same threshold/period/channel (or the same day's
     * spike/channel) is never queued twice even if the detection job runs on 2 instances at
     * once. Returns 1 if this call actually claimed it, 0 if some other call already had.
     */
    @Modifying
    @Query(value = """
            INSERT INTO budget_alert_log
                (id, alert_type, budget_id, period_start, threshold_percent, channel,
                 dedupe_key, spent_usd, limit_usd, status, attempt_count, next_attempt_at)
            VALUES (:id, :alertType, :budgetId, :periodStart, :thresholdPercent, :channel,
                    :dedupeKey, :spentUsd, :limitUsd, 'PENDING', 0, now())
            ON CONFLICT (dedupe_key) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("alertType") String alertType,
            @Param("budgetId") UUID budgetId, @Param("periodStart") LocalDate periodStart,
            @Param("thresholdPercent") Integer thresholdPercent, @Param("channel") String channel,
            @Param("dedupeKey") String dedupeKey, @Param("spentUsd") BigDecimal spentUsd,
            @Param("limitUsd") BigDecimal limitUsd);
}
