package com.unisage.backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The identity of the vectors currently stored in a Qdrant collection — plan.md "Embedding
 * identity guard". Keyed by {@code collection_name}, not a global singleton, so a future
 * re-index/multi-tenant migration doesn't need a schema change.
 *
 * <p>Never updated or deleted once inserted, enforced at 3 independent layers so a single bug
 * can't silently corrupt a live index's identity:
 * <ol>
 *   <li>This entity is {@link Immutable} — Hibernate never issues UPDATE/DELETE for it.</li>
 *   <li>{@code EmbeddingIndexIdentityRepository} exposes no update/delete method at all — writing
 *       a new identity only ever goes through {@code EmbeddingIndexIdentityWriter#insertIfAbsent},
 *       a single hardcoded {@code INSERT ... ON CONFLICT DO NOTHING} statement.</li>
 *   <li>V16 adds a {@code BEFORE UPDATE OR DELETE} trigger on the table that raises — a stray
 *       hand-written SQL statement or a future bug still can't mutate an established row.</li>
 * </ol>
 */
@Entity
@Immutable
@Table(name = "embedding_index_identity")
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmbeddingIndexIdentity {

    @Id
    @Column(name = "collection_name", updatable = false, nullable = false)
    private String collectionName;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "model_name", nullable = false, updatable = false)
    private String modelName;

    @Column(name = "model_source_ref", updatable = false)
    private String modelSourceRef;

    @Column(name = "api_base_url", updatable = false)
    private String apiBaseUrl;

    @Column(name = "dimension", nullable = false, updatable = false)
    private Integer dimension;

    /** Vector(s) of the fixed probe sentences, measured once at establish time. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fingerprint", columnDefinition = "real[]", nullable = false, updatable = false)
    private Float[] fingerprint;

    @CreationTimestamp
    @Column(name = "established_at", updatable = false)
    private LocalDateTime establishedAt;

    /** {@code "bootstrap-cli"} or {@code "first-upsert"} — see V16's CHECK constraint. */
    @Column(name = "established_by", nullable = false, updatable = false)
    private String establishedBy;
}
