package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.enums.MsgRole;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /**
     * Row-locks the message for a read-modify-write of its {@code metadata} jsonb (clarification
     * cancel, calculation feedback) so two concurrent writers cannot drop each other's keys.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Message m WHERE m.id = :id")
    Optional<Message> findByIdForUpdate(@Param("id") UUID id);

    /** The message of {@code role} sent immediately before {@code before} in a conversation. */
    Optional<Message> findFirstByConversationIdAndRoleAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID conversationId, MsgRole role, LocalDateTime before);

    /** Last COMPLETED message of a conversation - the cutoff of a new share link (UNISAGE-107). */
    @Query(value = "SELECT * FROM messages WHERE conversation_id = :conversationId AND status = 'COMPLETED' "
            + "ORDER BY created_at DESC, id DESC LIMIT 1", nativeQuery = true)
    Optional<Message> findLastCompleted(@Param("conversationId") UUID conversationId);

    /**
     * A share link's snapshot: the COMPLETED messages up to and including the cutoff, oldest first.
     * {@code (created_at, id)} row comparison, so a tie on created_at can't let a later row in.
     */
    @Query(value = "SELECT * FROM messages WHERE conversation_id = :conversationId AND status = 'COMPLETED' "
            + "AND (created_at, id) <= (CAST(:cutoffAt AS timestamp), CAST(:cutoffId AS uuid)) "
            + "ORDER BY created_at, id", nativeQuery = true)
    List<Message> findSnapshot(@Param("conversationId") UUID conversationId,
            @Param("cutoffAt") LocalDateTime cutoffAt, @Param("cutoffId") UUID cutoffId);

    @Query(value = "SELECT count(*) FROM messages WHERE conversation_id = :conversationId AND status = 'COMPLETED' "
            + "AND (created_at, id) <= (CAST(:cutoffAt AS timestamp), CAST(:cutoffId AS uuid))", nativeQuery = true)
    int countSnapshot(@Param("conversationId") UUID conversationId,
            @Param("cutoffAt") LocalDateTime cutoffAt, @Param("cutoffId") UUID cutoffId);

    /** Bulk delete for the guest-session cleanup job — bypasses conversation-by-conversation loading. */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.id IN "
            + "(SELECT c.id FROM Conversation c WHERE c.guestSession.id IN :guestSessionIds)")
    void deleteByConversationGuestSessionIdIn(@Param("guestSessionIds") Collection<UUID> guestSessionIds);
}
