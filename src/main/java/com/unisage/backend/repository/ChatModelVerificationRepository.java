package com.unisage.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
