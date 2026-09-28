package com.unisage.backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.unisage.backend.entity.enums.UsagePurpose;
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
 * One business request that made at least one LLM/embedding call - parent of {@link RequestUsageLine}.
 * Deliberately NOT extending {@link BaseEntity}: a usage log row is append-only (no updatedAt/
 * updatedBy churn - only Python's ingest endpoint ever writes it, once, via
 * {@code ON CONFLICT (request_id) DO NOTHING}), and it must not itself be soft-deleted (there is no
 * {@code isActive} concept for a cost record). {@link #startedAt}/{@link #finishedAt} - not
 * {@link #createdAt} - are what period/dashboard queries filter on (plan.md "Architecture
 * Decisions": {@code createdAt} via {@link CreationTimestamp} is JVM-clock bookkeeping only, never
 * relied on for period cuts).
 */
@Entity
@Table(name = "request_usage_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RequestUsageLog {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Idempotency key - Python generates it once per business request; UNIQUE, first-write-wins. */
    @Column(name = "request_id", nullable = false, updatable = false, unique = true)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private UsagePurpose purpose;

    /** ON DELETE SET NULL - a usage record outlives the conversation it was part of. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", updatable = false)
    private Conversation conversation;

    /** ON DELETE SET NULL - the USER message this request answered, if any (guest cleanup may purge it). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_message_id", updatable = false)
    private Message userMessage;

    /** ON DELETE SET NULL - the ASSISTANT message this request produced, if any. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assistant_message_id", updatable = false)
    private Message assistantMessage;

    /** ON DELETE SET NULL - null for a guest request (see {@link #guestIp}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    @Column(name = "guest_ip", updatable = false)
    private String guestIp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private UsageRequestStatus status;

    @Builder.Default
    @Column(name = "total_input_tokens", nullable = false)
    private Integer totalInputTokens = 0;

    @Builder.Default
    @Column(name = "total_output_tokens", nullable = false)
    private Integer totalOutputTokens = 0;

    @Builder.Default
    @Column(name = "total_cached_tokens", nullable = false)
    private Integer totalCachedTokens = 0;

    /** Sum of every priced line's {@code costUsd} - does NOT include unpriced lines' estimates. */
    @Builder.Default
    @Column(name = "total_cost_usd", nullable = false, precision = 18, scale = 8)
    private BigDecimal totalCostUsd = BigDecimal.ZERO;

    /** Sum of every UNPRICED line's {@code estimatedCostUsd} - shown separately, never folded into {@link #totalCostUsd}. */
    @Builder.Default
    @Column(name = "estimated_unpriced_cost_usd", nullable = false, precision = 18, scale = 8)
    private BigDecimal estimatedUnpricedCostUsd = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "unpriced_line_count", nullable = false)
    private Integer unpricedLineCount = 0;

    @Builder.Default
    @Column(name = "line_count", nullable = false)
    private Integer lineCount = 0;

    /** End-to-end latency of the whole request, in milliseconds. */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** UTC. Set by Java from {@code Clock}/parsed from the payload's ISO-8601 UTC timestamp - never from {@link #createdAt}. */
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at", updatable = false)
    private LocalDateTime finishedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
