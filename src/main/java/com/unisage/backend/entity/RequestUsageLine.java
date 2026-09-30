package com.unisage.backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsageRequestStatus;

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
 * One provider/embedding call within a {@link RequestUsageLog} - one row per attempt, including a
 * failed attempt before failover ({@link #attempt} counts from 0). {@link #provider}/
 * {@link #modelName}/{@link #sourceType} are a SNAPSHOT taken at call time, independent of
 * {@link #chatModel} (which is {@code ON DELETE SET NULL} and may later be edited/deleted) - the
 * cost history of a line must never change because the underlying {@code ChatModel} row changed
 * after the fact. Not extending {@link BaseEntity} for the same reason as {@link RequestUsageLog}.
 */
@Entity
@Table(name = "request_usage_lines")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RequestUsageLine {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** ON DELETE CASCADE - a line has no meaning once its parent request is gone. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usage_log_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RequestUsageLog usageLog;

    /** Order within the request - UNIQUE(usage_log_id, seq) at the DB level, not just validated in the service. */
    @Column(name = "seq", nullable = false, updatable = false)
    private Integer seq;

    /** Graph node name (e.g. {@code GenerationSynthesisNode}) or call site label (e.g. {@code embed_batch}). */
    @Column(name = "node_name", nullable = false, updatable = false)
    private String nodeName;

    /** 0 = first try, >= 1 = a failover retry of the same node/call within this request. */
    @Column(name = "attempt", nullable = false, updatable = false)
    private Integer attempt;

    /** ON DELETE SET NULL - kept only as a routing/reporting convenience; never trusted over the snapshot fields below. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_model_id", updatable = false)
    private ChatModel chatModel;

    @Column(name = "provider", updatable = false)
    private String provider;

    @Column(name = "model_name", updatable = false)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", columnDefinition = "varchar(20)", updatable = false)
    private ChatModelSourceType sourceType;

    @Builder.Default
    @Column(name = "input_tokens", nullable = false, updatable = false)
    private Integer inputTokens = 0;

    @Builder.Default
    @Column(name = "output_tokens", nullable = false, updatable = false)
    private Integer outputTokens = 0;

    @Builder.Default
    @Column(name = "cached_tokens", nullable = false, updatable = false)
    private Integer cachedTokens = 0;

    /** Null unless {@link #costStatus} is {@code PRICED} - see plan.md's costUsd=null rule. */
    @Column(name = "cost_usd", precision = 18, scale = 8, updatable = false)
    private BigDecimal costUsd;

    /** Always populated for CLOUD_API (even when PRICED, equal to {@link #costUsd}); zero for FREE. */
    @Column(name = "estimated_cost_usd", nullable = false, precision = 18, scale = 8, updatable = false)
    private BigDecimal estimatedCostUsd;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_status", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private UsageCostStatus costStatus;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private UsageRequestStatus status;

    @Column(name = "error_code", updatable = false)
    private String errorCode;

    /** UTC - when this attempt started. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;
}
