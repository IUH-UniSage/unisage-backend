package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Pinned first (newest pin first), then the rest by last update. */
    @Query("SELECT c FROM Conversation c WHERE c.user.id = :userId AND c.deletedAt IS NULL "
            + "ORDER BY c.pinnedAt DESC NULLS LAST, c.updatedAt DESC")
    List<Conversation> findActiveByUserId(@Param("userId") UUID userId);

    /**
     * Pins a conversation, keeping the original pin time if it is already pinned. Like
     * {@link #unpin}, a bulk JPQL UPDATE: it bypasses the JPA auditing listener, so
     * {@code updated_at}/{@code updated_by} stay as they were and (un)pinning never reorders the
     * "recent" part of the list. Clears the persistence context so a re-read sees the new value.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Conversation c SET c.pinnedAt = COALESCE(c.pinnedAt, :now) WHERE c.id = :id")
    int pin(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** See {@link #pin}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Conversation c SET c.pinnedAt = NULL WHERE c.id = :id")
    int unpin(@Param("id") UUID id);

    @Query("SELECT c FROM Conversation c WHERE c.guestSession.id = :guestSessionId AND c.user IS NULL "
            + "AND c.deletedAt IS NULL ORDER BY c.updatedAt DESC")
    List<Conversation> findActiveUnclaimedByGuestSessionId(@Param("guestSessionId") UUID guestSessionId);

    /**
     * Soft-deletes every active conversation beyond the {@code keep} most recently used (latest
     * message, or creation time if it has none) of its owner
     * (a user, or an unclaimed guest session - {@code conversations_owner_xor} guarantees exactly
     * one of the two is set, so they never share a partition). One statement for the whole table
     * instead of a loop per owner. Returns the number of conversations trimmed.
     *
     * <p>The same statement revokes every live share link of the trimmed conversations
     * (UNISAGE-107 R11): a data-modifying CTE always runs to completion even though the outer
     * UPDATE doesn't read it, and both see the same {@code doomed} set, so the two writes are
     * atomic and can't disagree on which conversations were deleted.
     */
    @Modifying
    @Query(value = "WITH doomed AS ("
            + "SELECT id FROM (SELECT c.id, ROW_NUMBER() OVER ("
            + "PARTITION BY COALESCE(c.user_id, c.guest_session_id) "
            + "ORDER BY COALESCE((SELECT MAX(m.created_at) FROM messages m WHERE m.conversation_id = c.id), "
            + "c.created_at) DESC, c.created_at DESC) AS rn "
            + "FROM conversations c WHERE c.deleted_at IS NULL) ranked WHERE rn > :keep), "
            + "revoked AS (UPDATE conversation_shares SET revoked_at = now() "
            + "WHERE revoked_at IS NULL AND conversation_id IN (SELECT id FROM doomed) RETURNING id) "
            + "UPDATE conversations SET deleted_at = now() WHERE id IN (SELECT id FROM doomed)",
            nativeQuery = true)
    int softDeleteBeyondNewestPerOwner(@Param("keep") int keep);

    /**
     * "Xóa tất cả" in the chat sidebar: marks every live conversation of the user inactive
     * ({@code deleted_at} set, {@code is_active = false}) - the same soft delete a single delete
     * does, so the list queries above stop returning them. Returns how many were deleted.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Conversation c SET c.deletedAt = :now, c.isActive = false "
            + "WHERE c.user.id = :userId AND c.deletedAt IS NULL")
    int softDeleteAllActiveByUserId(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** The caller's live copy made from {@code shareId} by "Tiếp tục", if any. */
    @Query("SELECT c FROM Conversation c WHERE c.sourceShare.id = :shareId AND c.user.id = :userId "
            + "AND c.deletedAt IS NULL")
    Optional<Conversation> findActiveCopy(@Param("shareId") UUID shareId, @Param("userId") UUID userId);

    /**
     * Bulk delete for the guest-session cleanup job. Never touches a claimed conversation: the
     * {@code conversations_owner_xor} CHECK constraint guarantees {@code guest_session_id} is
     * only non-null on rows {@code claim()} hasn't taken ownership of yet.
     */
    @Modifying
    @Query("DELETE FROM Conversation c WHERE c.guestSession.id IN :guestSessionIds")
    void deleteByGuestSessionIdIn(@Param("guestSessionIds") Collection<UUID> guestSessionIds);
}
