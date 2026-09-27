package com.unisage.backend.entity;

import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.security.ApiKeyConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A pull-based verification job for a {@link ChatModel} candidate — plan.md "Verification
 * lifecycle". Only Java transitions {@link #status}; Python/Celery only sees claim/result HTTP
 * responses (200/409), never this enum directly. {@link #leaseToken} is the fencing token
 * (regenerated on every claim); {@link #lastResultLeaseToken} makes result submission idempotent.
 * {@link #candidateGeneration} + {@link #baseRevision} are the compare-and-set condition that
 * promote checks against the row, so a credential edited mid-verify can never be promoted by a
 * stale worker (plan.md "Credential rotation").
 */
@Entity
@Table(name = "chat_model_verifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatModelVerification {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.DETACH)
    @JoinColumn(name = "chat_model_id", nullable = false, updatable = false)
    private ChatModel chatModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private ChatModelVerificationStatus status;

    /** Snapshot of {@code ChatModel.candidateGeneration} at job creation — the CAS condition on promote. */
    @Column(name = "candidate_generation", nullable = false)
    private Integer candidateGeneration;

    /** Snapshot of {@code ChatModel.revision} at job creation — the other half of the CAS condition on promote. */
    @Column(name = "base_revision", nullable = false)
    private Integer baseRevision;

    @Column(name = "candidate_llm_provider")
    private String candidateLlmProvider;

    @Column(name = "candidate_llm_model_name")
    private String candidateLlmModelName;

    @Column(name = "candidate_model_source_ref")
    private String candidateModelSourceRef;

    /** Encrypted the same way as {@code ChatModel.apiKeyEncrypted} — see {@link ApiKeyConverter}. */
    @Convert(converter = ApiKeyConverter.class)
    @Column(name = "candidate_api_key_encrypted", columnDefinition = "text")
    private String candidateApiKeyEncrypted;

    @Column(name = "candidate_api_base_url")
    private String candidateApiBaseUrl;

    /** EMBEDDING only — measured by the verifier, compared against {@code embedding_index_identity}. */
    @Column(name = "embedding_dimension")
    private Integer embeddingDimension;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "embedding_fingerprint", columnDefinition = "real[]")
    private Float[] embeddingFingerprint;

    @Builder.Default
    @Column(name = "attempt", nullable = false)
    private Integer attempt = 0;

    @Builder.Default
    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 3;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** Set on claim to {@code now() + 60s}; result is only accepted while {@code lease_until > now()}. */
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    /** Fencing token — a fresh random UUID on every claim, never reused. */
    @Column(name = "lease_token")
    private UUID leaseToken;

    /** Token of the last result accepted for this job — makes a retried result submission idempotent. */
    @Column(name = "last_result_lease_token")
    private UUID lastResultLeaseToken;

    @Column(name = "error_type", columnDefinition = "varchar(20)")
    private String errorType;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
