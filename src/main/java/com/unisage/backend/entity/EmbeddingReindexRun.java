package com.unisage.backend.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.CascadeType;
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

import org.hibernate.annotations.CreationTimestamp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.unisage.backend.entity.enums.EmbeddingReindexStatus;

/**
 * One re-embed of every point of {@link #sourceCollection} into {@link #targetCollection} with
 * {@link #targetChatModel} — changes/17-10-2026-Embedding-Reindex/plan.md. Claimed by
 * unisage-agent under a lease like {@link ChatModelVerification}; {@link #leaseToken} is the
 * fencing token, regenerated on every claim.
 */
@Entity
@Table(name = "embedding_reindex_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmbeddingReindexRun {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "base_collection", nullable = false, updatable = false)
    private String baseCollection;

    @Column(name = "source_collection", nullable = false, updatable = false)
    private String sourceCollection;

    @Column(name = "target_collection", nullable = false, updatable = false)
    private String targetCollection;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.DETACH)
    @JoinColumn(name = "target_chat_model_id", nullable = false, updatable = false)
    private ChatModel targetChatModel;

    /** {@code ChatModel.revision} when the run was requested — an edit since then fails the cutover. */
    @Column(name = "target_model_revision", nullable = false, updatable = false)
    private Integer targetModelRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private EmbeddingReindexStatus status;

    /**
     * True = the target is the live model itself: the agent copies the dense vectors and adds
     * the {@code bm25} sparse vector, no embedding call; the cutover only moves the pointer.
     */
    @Builder.Default
    @Column(name = "rebuild", nullable = false, updatable = false)
    private boolean rebuild = false;

    @Column(name = "total_points")
    private Integer totalPoints;

    @Builder.Default
    @Column(name = "processed_points", nullable = false)
    private Integer processedPoints = 0;

    @Builder.Default
    @Column(name = "failed_points", nullable = false)
    private Integer failedPoints = 0;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.DETACH)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
