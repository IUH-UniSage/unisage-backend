package com.unisage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Query("SELECT c FROM Conversation c WHERE c.user.id = :userId AND c.deletedAt IS NULL ORDER BY c.updatedAt DESC")
    List<Conversation> findActiveByUserId(@Param("userId") UUID userId);

    @Query("SELECT c FROM Conversation c WHERE c.guestSession.id = :guestSessionId AND c.user IS NULL "
            + "AND c.deletedAt IS NULL ORDER BY c.updatedAt DESC")
    List<Conversation> findActiveUnclaimedByGuestSessionId(@Param("guestSessionId") UUID guestSessionId);

    /**
     * Soft-deletes every active conversation beyond the {@code keep} most recently used (latest
     * message, or creation time if it has none) of its owner
     * (a user, or an unclaimed guest session - {@code conversations_owner_xor} guarantees exactly
     * one of the two is set, so they never share a partition). One statement for the whole table
     * instead of a loop per owner. Returns the number of conversations trimmed.
     */
    @Modifying
    @Query(value = "UPDATE conversations SET deleted_at = now() WHERE id IN ("
            + "SELECT id FROM (SELECT c.id, ROW_NUMBER() OVER ("
            + "PARTITION BY COALESCE(c.user_id, c.guest_session_id) "
            + "ORDER BY COALESCE((SELECT MAX(m.created_at) FROM messages m WHERE m.conversation_id = c.id), "
            + "c.created_at) DESC, c.created_at DESC) AS rn "
            + "FROM conversations c WHERE c.deleted_at IS NULL) ranked WHERE rn > :keep)",
            nativeQuery = true)
    int softDeleteBeyondNewestPerOwner(@Param("keep") int keep);

    /**
     * Bulk delete for the guest-session cleanup job. Never touches a claimed conversation: the
     * {@code conversations_owner_xor} CHECK constraint guarantees {@code guest_session_id} is
     * only non-null on rows {@code claim()} hasn't taken ownership of yet.
     */
    @Modifying
    @Query("DELETE FROM Conversation c WHERE c.guestSession.id IN :guestSessionIds")
    void deleteByGuestSessionIdIn(@Param("guestSessionIds") Collection<UUID> guestSessionIds);
}
