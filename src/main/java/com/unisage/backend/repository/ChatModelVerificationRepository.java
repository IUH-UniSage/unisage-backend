package com.unisage.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;

public interface ChatModelVerificationRepository extends JpaRepository<ChatModelVerification, UUID> {

    /**
     * Selects up to {@code limit} claimable job ids — {@code QUEUED} ready for its next attempt, or
     * {@code RUNNING} with an expired lease (a worker died mid-verify) — and locks them
     * {@code FOR UPDATE SKIP LOCKED} so two concurrent callers never claim the same job. The actual
     * claim (set RUNNING, fresh lease_token/lease_until, attempt += 1) is a separate write done by
     * the caller inside the same transaction (Task 6) — this method only does the race-safe
     * selection, which is the part that has to be a native query: JPQL has no
     * {@code FOR UPDATE SKIP LOCKED}.
     */
    @Query(value = """
            SELECT id FROM chat_model_verifications
            WHERE (status = 'QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= now()))
               OR (status = 'RUNNING' AND lease_until < now())
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> findClaimableIds(@Param("limit") int limit);

    /**
     * Scalar-only lookup of the owning {@code ChatModel} id — used by the result endpoint (Task 6)
     * to discover which model row to lock first, WITHOUT loading a {@code ChatModelVerification}
     * entity into the persistence context. Loading the entity here (even unlocked) would leave a
     * stale managed instance behind: a later {@code SELECT ... FOR UPDATE} on the same id acquires
     * the correct row lock at the database, but Hibernate returns the already-cached Java object
     * rather than refreshing its fields from the new result set, so the caller would evaluate the
     * lease/CAS checks against pre-lock data. A projection query sidesteps this entirely.
     */
    @Query("SELECT v.chatModel.id FROM ChatModelVerification v WHERE v.id = :id")
    Optional<UUID> findChatModelIdById(@Param("id") UUID id);

    /** Open jobs (QUEUED/RUNNING) for a credential — used by Task 3/6 to supersede/cancel on edit/delete. */
    List<ChatModelVerification> findByChatModelIdAndStatusIn(UUID chatModelId, List<ChatModelVerificationStatus> statuses);

    /** Most recent job for a credential, regardless of status — backs the SA response's {@code latestVerification}. */
    Optional<ChatModelVerification> findFirstByChatModelIdOrderByCreatedAtDesc(UUID chatModelId);

    /**
     * Supersedes every still-open job for a credential in one statement (plan.md "Credential
     * rotation" — editing/re-verifying while a candidate is pending must not leave the old job
     * claimable). Called immediately before a new {@code QUEUED} job is inserted, same transaction.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatModelVerification v SET v.status = com.unisage.backend.entity.enums.ChatModelVerificationStatus.SUPERSEDED, "
            + "v.finishedAt = CURRENT_TIMESTAMP "
            + "WHERE v.chatModel.id = :chatModelId AND v.status IN ("
            + "com.unisage.backend.entity.enums.ChatModelVerificationStatus.QUEUED, "
            + "com.unisage.backend.entity.enums.ChatModelVerificationStatus.RUNNING)")
    int supersedeOpenJobs(@Param("chatModelId") UUID chatModelId);

    /**
     * Locks a single job row for the duration of the transaction — used by the result endpoint
     * (Task 6), always called AFTER the corresponding {@code ChatModel} row has already been
     * locked via {@link ChatModelRepository#findByIdForUpdate} (plan.md "Verification lifecycle"
     * step 4.1 — model, then job, fixed order to avoid deadlock with staged rotation).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ChatModelVerification v WHERE v.id = :id")
    Optional<ChatModelVerification> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The DB-clock half of the result endpoint's lease check (plan.md R3.1: "Lease phải kiểm cả
     * thời hạn" using {@code now()}, never the JVM/Python clock). Read against the row already
     * locked by {@link #findByIdForUpdate} in the same transaction — this does not re-lock, it
     * only evaluates {@code lease_until > now()} at the database.
     *
     * @return {@code true} if the job is still {@code RUNNING} with this exact lease token and an
     *         unexpired lease — the full condition from plan.md step 4.2, evaluated in one round trip.
     */
    @Query(value = """
            SELECT status = 'RUNNING' AND lease_token = :token AND lease_until > now()
            FROM chat_model_verifications
            WHERE id = :id
            """, nativeQuery = true)
    boolean isLeaseCurrentlyValid(@Param("id") UUID id, @Param("token") UUID token);
}
