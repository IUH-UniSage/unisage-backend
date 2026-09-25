package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
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

    /** Open jobs (QUEUED/RUNNING) for a credential — used by Task 6 to supersede/cancel on edit/delete. */
    List<ChatModelVerification> findByChatModelIdAndStatusIn(UUID chatModelId, List<ChatModelVerificationStatus> statuses);
}
