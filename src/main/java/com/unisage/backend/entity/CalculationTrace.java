package com.unisage.backend.entity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
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
 * Full diagnostic trace of one calculation item (T1..T3) of an assistant message, pushed by
 * unisage-agent - SPEC-calculation-node §7.2(b). Staff-only: no public API reads it; it is copied
 * into the item's {@code AI_CALCULATION_WRONG} ticket. Written only through the native upsert in
 * {@code CalculationTraceRepository}, so no auditing fields (the agent has no user identity).
 */
@Entity
@Table(name = "calculation_traces")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CalculationTrace {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(name = "item_id", nullable = false, length = 4, updatable = false)
    private String itemId;

    @Column(name = "run_id", length = 100)
    private String runId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> trace;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
