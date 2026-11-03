package com.unisage.backend.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A share link of a conversation (UNISAGE-107). The snapshot is a reference, not a copy: every
 * COMPLETED message of {@code conversation} with {@code (created_at, id) <= (cutoffAt,
 * cutoffMessageId)}. See docs/adr/0011-conversation-share-snapshot-and-token.md.
 */
@Entity
@Table(name = "conversation_shares")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class ConversationShare extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** 32 random bytes, base64url without padding (43 chars). Stored in clear on purpose (ADR 0011). */
    @Column(nullable = false, unique = true, length = 64, updatable = false)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    /** The conversation's title when the link was made. */
    private String title;

    /** Last message of the snapshot. */
    @Column(name = "cutoff_message_id", nullable = false, updatable = false)
    private UUID cutoffMessageId;

    /** {@code created_at} of {@link #cutoffMessageId}, as read back from the database. */
    @Column(name = "cutoff_at", nullable = false, updatable = false)
    private LocalDateTime cutoffAt;

    @Column(name = "message_count", nullable = false, updatable = false)
    private int messageCount;

    /** Set once, by the owner, or when the conversation is deleted; a revoked link is never shown again. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
