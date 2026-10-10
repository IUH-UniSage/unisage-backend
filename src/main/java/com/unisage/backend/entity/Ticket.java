package com.unisage.backend.entity;

import java.util.UUID;

import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A user's report about one AI answer. At most one regular Report per message, plus at most one
 * {@code AI_CALCULATION_WRONG} ticket per calculation item of that message (partial unique indexes,
 * V34); there is no assignee and no delete — staff only move {@code status} and write
 * {@code resolution}.
 */
@Entity
@Table(name = "tickets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class Ticket extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false, updatable = false)
    private Message message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TicketStatus status = TicketStatus.OPEN;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String resolution;

    /** {@code T1}..{@code T3} on an {@code AI_CALCULATION_WRONG} ticket, null on every other type. */
    @Column(name = "calculation_item_id", length = 4, updatable = false)
    private String calculationItemId;
}
