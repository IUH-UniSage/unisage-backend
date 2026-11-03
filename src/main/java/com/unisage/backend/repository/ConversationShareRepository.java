package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.ConversationShare;

public interface ConversationShareRepository extends JpaRepository<ConversationShare, UUID> {

    /**
     * A link that can still be opened: not revoked and its conversation not deleted. Anything else
     * looks exactly like an unknown token to the caller ({@code SHARE_NOT_FOUND}).
     */
    @Query("SELECT s FROM ConversationShare s JOIN FETCH s.conversation c "
            + "WHERE s.token = :token AND s.revokedAt IS NULL AND c.deletedAt IS NULL")
    Optional<ConversationShare> findActiveByToken(@Param("token") String token);

    @Query("SELECT s FROM ConversationShare s WHERE s.id = :id AND s.owner.id = :ownerId")
    Optional<ConversationShare> findByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Query(value = "SELECT s FROM ConversationShare s WHERE s.owner.id = :ownerId AND s.revokedAt IS NULL",
            countQuery = "SELECT count(s) FROM ConversationShare s WHERE s.owner.id = :ownerId AND s.revokedAt IS NULL")
    Page<ConversationShare> findActiveByOwnerId(@Param("ownerId") UUID ownerId, Pageable pageable);

    /** "Thu hồi tất cả". Returns how many live links were revoked. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ConversationShare s SET s.revokedAt = :now "
            + "WHERE s.owner.id = :ownerId AND s.revokedAt IS NULL")
    int revokeAllByOwnerId(@Param("ownerId") UUID ownerId, @Param("now") LocalDateTime now);

    /** Deleting a conversation revokes its links (UNISAGE-107 R11). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE ConversationShare s SET s.revokedAt = :now "
            + "WHERE s.conversation.id = :conversationId AND s.revokedAt IS NULL")
    int revokeAllByConversationId(@Param("conversationId") UUID conversationId, @Param("now") LocalDateTime now);
}
