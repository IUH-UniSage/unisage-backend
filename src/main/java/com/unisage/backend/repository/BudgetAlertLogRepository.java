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
import com.unisage.backend.entity.enums.AlertStatus;
import com.unisage.backend.entity.enums.AlertType;

public interface BudgetAlertLogRepository extends JpaRepository<BudgetAlertLog, UUID> {

    List<BudgetAlertLog> findByChannelAndDismissedAtIsNull(AlertChannel channel);

    @Query("""
            SELECT b FROM BudgetAlertLog b
            WHERE (:alertType IS NULL OR b.alertType = :alertType)
              AND (:channel IS NULL OR b.channel = :channel)
              AND (:status IS NULL OR b.status = :status)
            ORDER BY b.createdAt DESC
            """)
    Page<BudgetAlertLog> search(@Param("alertType") AlertType alertType,
            @Param("channel") AlertChannel channel, @Param("status") AlertStatus status, Pageable pageable);

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

    /**
     * Locks and returns up to {@code limit} rows ready to (re)send, skipping any row another
     * transaction already has locked - 2 dispatcher instances running at once split the work
     * instead of racing to send the same alert twice. Must be called from inside a
     * {@code @Transactional} method: the lock only holds for the duration of that transaction, so
     * the caller's status update has to happen before it commits.
     */
    @Query(value = """
            SELECT * FROM budget_alert_log
            WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= now()
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BudgetAlertLog> claimReadyToSend(@Param("limit") int limit);
}
