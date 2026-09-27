package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.enums.ChatModelStatus;

public interface ChatModelRepository extends JpaRepository<ChatModel, UUID>, JpaSpecificationExecutor<ChatModel> {

    @Override
    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    Page<ChatModel> findAll(Pageable pageable);

    /** Backs {@code GET /chat-models}'s modelPurpose/status/isActive filters (built as a {@link Specification}). */
    @Override
    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    Page<ChatModel> findAll(Specification<ChatModel> spec, Pageable pageable);

    /**
     * Locks the row for the duration of the transaction — used by staged rotation
     * (create/update/verify, plan.md "Credential rotation" — "khoá model → job") so an update
     * that supersedes the in-flight job and creates a new one can't interleave with a concurrent
     * writer on the same row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ChatModel c WHERE c.id = :id")
    Optional<ChatModel> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Generic compare-and-set transition used by every SA-triggered status change (plan.md
     * "State machine" — "Mọi chuyển trạng thái đi qua 1 method service dùng compare-and-set").
     *
     * @return 1 if the row was at {@code from} and is now {@code to}, 0 if the race was lost (or
     *         the row was never at {@code from}) — caller maps 0 to {@code CHAT_MODEL_STATUS_CONFLICT}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatModel c SET c.status = :to WHERE c.id = :id AND c.status = :from")
    int updateStatusIfCurrent(
            @Param("id") UUID id,
            @Param("from") ChatModelStatus from,
            @Param("to") ChatModelStatus to);

    /**
     * Deactivate transition — valid from any status other than already-{@code INACTIVE} (plan.md
     * "State machine": PENDING/ACTIVE/DISABLED → deactivate → INACTIVE; INACTIVE → deactivate is
     * undefined/idempotent-reject). Written as "not already the target" rather than a fixed
     * {@code from} because 3 different source statuses share the same target.
     *
     * @return 1 if transitioned, 0 if the row was already INACTIVE (caller maps 0 to a 409).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatModel c SET c.status = com.unisage.backend.entity.enums.ChatModelStatus.INACTIVE "
            + "WHERE c.id = :id AND c.status <> com.unisage.backend.entity.enums.ChatModelStatus.INACTIVE")
    int deactivate(@Param("id") UUID id);

    /**
     * Part 1 of the embedding activate/swap transaction (plan.md "State machine" — "Xử lý race" /
     * "Embedding identity guard"): moves whichever EMBEDDING row is currently ACTIVE (if any,
     * other than {@code excludeId}) to INACTIVE, so at most one EMBEDDING row is ever ACTIVE
     * before part 2 (the CAS in {@link #updateStatusIfCurrent}) tries to activate the target row.
     * The unique partial index is still what actually enforces "at most 1 active embedding" under
     * a real race — this statement alone does not (see service-level handling of
     * {@code DataIntegrityViolationException}).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatModel c SET c.status = com.unisage.backend.entity.enums.ChatModelStatus.INACTIVE "
            + "WHERE c.modelPurpose = com.unisage.backend.entity.enums.ChatModelPurpose.EMBEDDING "
            + "AND c.status = com.unisage.backend.entity.enums.ChatModelStatus.ACTIVE AND c.id <> :excludeId")
    int deactivateOtherActiveEmbedding(@Param("excludeId") UUID excludeId);

    /**
     * Promotes a verified candidate onto the row — plan.md "Credential rotation": the CAS
     * condition ({@code candidateGeneration}/{@code revision} both matching what the job was
     * created against) is what stops a stale worker's result from clobbering a newer edit even
     * with a still-valid lease token (Task 6 fencing is a separate, additional guard on top of
     * this one). {@code newStatus} is fixed by the caller from the state-machine matrix (PENDING/
     * DISABLED -> ACTIVE or INACTIVE depending on purpose; ACTIVE/INACTIVE stay as they are).
     *
     * @return 1 if promoted, 0 if the generation/revision no longer matched (caller marks the job SUPERSEDED).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatModel c SET c.llmProvider = :provider, c.llmModelName = :modelName, "
            + "c.modelSourceRef = :sourceRef, c.apiKeyEncrypted = :apiKeyEncrypted, c.apiBaseUrl = :baseUrl, "
            + "c.embeddingDimension = :dimension, c.embeddingFingerprint = :fingerprint, "
            + "c.revision = c.revision + 1, c.verifiedAt = CURRENT_TIMESTAMP, c.status = :newStatus "
            + "WHERE c.id = :id AND c.candidateGeneration = :generation AND c.revision = :baseRevision")
    int promoteCandidate(
            @Param("id") UUID id,
            @Param("provider") String provider,
            @Param("modelName") String modelName,
            @Param("sourceRef") String sourceRef,
            @Param("apiKeyEncrypted") String apiKeyEncrypted,
            @Param("baseUrl") String baseUrl,
            @Param("dimension") Integer dimension,
            @Param("fingerprint") Float[] fingerprint,
            @Param("newStatus") ChatModelStatus newStatus,
            @Param("generation") Integer generation,
            @Param("baseRevision") Integer baseRevision);

    /**
     * Atomic counter/last-error update for a health report — comparing {@code revision} and
     * writing in the same statement (rather than read-then-write) is what makes a stale report a
     * no-op instead of a race (plan.md "Internal API contract" endpoint #3, R2.6).
     *
     * @return 1 if the row's current revision matched and was updated, 0 if it was stale (caller
     *         treats 0 as "ignore the whole report").
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatModel c SET c.errorCount = c.errorCount + 1, c.lastErrorAt = :occurredAt, "
            + "c.lastErrorCode = :errorCode, c.lastErrorMessage = :message "
            + "WHERE c.id = :id AND c.revision = :revision")
    int recordHealthError(
            @Param("id") UUID id,
            @Param("revision") Integer revision,
            @Param("occurredAt") LocalDateTime occurredAt,
            @Param("errorCode") String errorCode,
            @Param("message") String message);

    /**
     * Backs the internal snapshot endpoint (plan.md "Internal API contract" endpoint #1): only rows
     * routing actually uses ({@code is_active = true AND status = 'ACTIVE'}), ordered so the service
     * layer can group by purpose and keep priority order within each group without re-sorting.
     */
    @Query("SELECT c FROM ChatModel c WHERE c.isActive = true "
            + "AND c.status = com.unisage.backend.entity.enums.ChatModelStatus.ACTIVE "
            + "ORDER BY c.modelPurpose ASC, c.priority ASC")
    List<ChatModel> findAllActiveForSnapshot();

    /**
     * Compare-and-set circuit breaker: only disables the row if it is still {@code ACTIVE} at the
     * moment this runs — same pattern as every other state-machine transition (plan.md "State
     * machine"). A credential already {@code DISABLED}/{@code INACTIVE} simply doesn't match, so
     * this is a safe no-op (0 rows) rather than an error.
     *
     * @return 1 if the row was ACTIVE and is now DISABLED, 0 otherwise.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatModel c SET c.status = com.unisage.backend.entity.enums.ChatModelStatus.DISABLED "
            + "WHERE c.id = :id AND c.status = com.unisage.backend.entity.enums.ChatModelStatus.ACTIVE")
    int disableIfActive(@Param("id") UUID id);
}
