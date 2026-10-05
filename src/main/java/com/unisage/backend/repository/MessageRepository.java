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

import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.repository.projection.DailyMessageCount;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** The message of {@code role} sent immediately before {@code before} in a conversation. */
    Optional<Message> findFirstByConversationIdAndRoleAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID conversationId, MsgRole role, LocalDateTime before);

    /** UNISAGE-72: dashboard's "AI answers today" tile. */
    long countByRoleAndCreatedAtBetween(MsgRole role, LocalDateTime from, LocalDateTime to);

    /**
     * How many of those assistant replies carry at least one citation - {@code citations} is a
     * jsonb array column, so this needs {@code jsonb_array_length} rather than a JPQL predicate.
     */
    @Query(value = """
        SELECT COUNT(*) FROM messages
        WHERE role = 'ASSISTANT' AND created_at BETWEEN :from AND :to
          AND citations IS NOT NULL AND jsonb_array_length(citations) > 0
        """, nativeQuery = true)
    long countAssistantWithCitationsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * UNISAGE-72: dashboard's weekly activity chart - one row per day since {@code from} (UTC),
     * days cut in {@code zone} ({@code created_at} is stored as UTC).
     */
    @Query(value = """
        SELECT CAST((created_at AT TIME ZONE 'UTC') AT TIME ZONE :zone AS date) AS day, COUNT(*) AS count
        FROM messages
        WHERE role = 'ASSISTANT' AND created_at >= :from
        GROUP BY day
        ORDER BY day
        """, nativeQuery = true)
    List<DailyMessageCount> countDailyAssistantMessagesSince(
            @Param("from") LocalDateTime from, @Param("zone") String zone);

    /** Bulk delete for the guest-session cleanup job — bypasses conversation-by-conversation loading. */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.id IN "
            + "(SELECT c.id FROM Conversation c WHERE c.guestSession.id IN :guestSessionIds)")
    void deleteByConversationGuestSessionIdIn(@Param("guestSessionIds") Collection<UUID> guestSessionIds);
}
