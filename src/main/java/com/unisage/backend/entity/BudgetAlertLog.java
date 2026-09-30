package com.unisage.backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.unisage.backend.entity.enums.AlertChannel;
import com.unisage.backend.entity.enums.AlertStatus;
import com.unisage.backend.entity.enums.AlertType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One claimed send of one alert on one channel - plan.md "BudgetAlertLog", "Vòng đời gửi".
 * {@link #dedupeKey} is UNIQUE and claimed via {@code INSERT ... ON CONFLICT DO NOTHING}, so the
 * same threshold/period/channel (or the same day's spike/channel) is never queued twice even if the
 * detection job runs on 2 instances at once. Not extending {@link BaseEntity}: append-only,
 * mutated only by the send job itself, never by a user CRUD flow.
 */
@Entity
@Table(name = "budget_alert_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BudgetAlertLog {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private AlertType alertType;

    /** NULL for SPIKE - a spike isn't tied to any one budget. ON DELETE SET NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", updatable = false)
    private Budget budget;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    /** NULL for SPIKE. */
    @Column(name = "threshold_percent", updatable = false)
    private Integer thresholdPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private AlertChannel channel;

    @Column(name = "dedupe_key", nullable = false, updatable = false, unique = true)
    private String dedupeKey;

    @Column(name = "spent_usd", nullable = false, precision = 18, scale = 8, updatable = false)
    private BigDecimal spentUsd;

    /** NULL for SPIKE. */
    @Column(name = "limit_usd", precision = 18, scale = 8, updatable = false)
    private BigDecimal limitUsd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private AlertStatus status;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** IN_APP only - dismiss is a shared state for every SA, not per-user. */
    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dismissed_by")
    private User dismissedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
