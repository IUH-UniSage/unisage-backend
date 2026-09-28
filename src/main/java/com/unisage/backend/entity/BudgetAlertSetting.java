package com.unisage.backend.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Singleton row (always {@code id = 1}, enforced by a DB {@code CHECK} - V26) holding the global
 * alert configuration - plan.md "BudgetAlertSetting". Only {@code GET}/{@code PUT} exist for this
 * resource, never {@code POST}/{@code DELETE}, so a second row can never be created even under
 * concurrent requests. Not extending {@link BaseEntity}: this is a config singleton, not a
 * soft-deletable resource, and {@code createdBy}/{@code isActive} have no meaning for it.
 */
@Entity
@Table(name = "budget_alert_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BudgetAlertSetting {

    @Id
    private Short id;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "thresholds_percent", columnDefinition = "integer[]", nullable = false)
    private Integer[] thresholdsPercent = new Integer[] {50, 80, 100};

    @Builder.Default
    @Column(name = "spike_detection_enabled", nullable = false)
    private Boolean spikeDetectionEnabled = false;

    /** Spike = today's spend more than this % higher than the trailing-7-day average. */
    @Builder.Default
    @Column(name = "spike_threshold_percent", nullable = false)
    private Integer spikeThresholdPercent = 50;

    @Builder.Default
    @Column(name = "in_app_enabled", nullable = false)
    private Boolean inAppEnabled = true;

    @Builder.Default
    @Column(name = "email_enabled", nullable = false)
    private Boolean emailEnabled = false;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "email_recipients", columnDefinition = "text[]", nullable = false)
    private String[] emailRecipients = new String[0];

    /** DB-side toggle only - has no effect unless BUDGET_ALERT_SLACK_ENABLED + a webhook URL are
     * also set in env (plan.md "Cấu hình Python"/"Architecture Decisions"). */
    @Builder.Default
    @Column(name = "slack_enabled", nullable = false)
    private Boolean slackEnabled = false;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
