package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Query("SELECT c FROM Conversation c WHERE c.user.id = :userId AND c.deletedAt IS NULL ORDER BY c.updatedAt DESC")
    List<Conversation> findActiveByUserId(@Param("userId") UUID userId);

    @Query("SELECT c FROM Conversation c WHERE c.guestSession.id = :guestSessionId AND c.user IS NULL "
            + "AND c.deletedAt IS NULL ORDER BY c.updatedAt DESC")
    List<Conversation> findActiveUnclaimedByGuestSessionId(@Param("guestSessionId") UUID guestSessionId);
}
