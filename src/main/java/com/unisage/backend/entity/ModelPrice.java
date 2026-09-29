package com.unisage.backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.ModelPriceSource;

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

/** USD per 1M tokens. Timestamps are UTC (set from {@code Clock}), so this does not extend
 * {@link BaseEntity}, whose auditing timestamps use the JVM's local time. */
@Entity
@Table(name = "model_prices")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ModelPrice {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "model_name", nullable = false, updatable = false)
    private String modelName;

    @Column(name = "input_per_million", nullable = false, precision = 18, scale = 8)
    private BigDecimal inputPerMillion;

    /** Null for embedding models, which have no output tokens. */
    @Column(name = "output_per_million", precision = 18, scale = 8)
    private BigDecimal outputPerMillion;

    /** Null means cached input tokens are billed at {@link #inputPerMillion}. */
    @Column(name = "cached_input_per_million", precision = 18, scale = 8)
    private BigDecimal cachedInputPerMillion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", columnDefinition = "varchar(20)", nullable = false)
    private ModelPriceSource source;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
