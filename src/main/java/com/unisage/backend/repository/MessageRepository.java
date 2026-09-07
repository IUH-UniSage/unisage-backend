package com.unisage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.Message;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** Bulk delete for the guest-session cleanup job — bypasses conversation-by-conversation loading. */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.id IN "
            + "(SELECT c.id FROM Conversation c WHERE c.guestSession.id IN :guestSessionIds)")
    void deleteByConversationGuestSessionIdIn(@Param("guestSessionIds") Collection<UUID> guestSessionIds);
}
