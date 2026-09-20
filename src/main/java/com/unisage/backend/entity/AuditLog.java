package com.unisage.backend.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable, append-only audit trail row. Deliberately NOT extending {@link BaseEntity}: an
 * audit-log row is never updated after it is written (no updatedAt/updatedBy), and it must not
 * itself be audited (that would recurse). Actor identity is denormalized (snapshotted) at write
 * time rather than a live FK to {@link User}, so a row keeps reading correctly even if the actor
 * is later renamed or deleted — see docs/adr/0003-audit-log-persistence.md.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    // Denormalized actor snapshot — NOT a FK to users(id). Null for genuinely unauthenticated /
    // system-triggered writes (e.g. guest chat paths).
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_name", updatable = false)
    private String actorName;

    @Column(name = "actor_code", updatable = false)
    private String actorCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", updatable = false, nullable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", updatable = false, nullable = false)
    private ResourceType resourceType;

    @Column(name = "resource_id", updatable = false)
    private String resourceId;

    // JSON (serialized as plain text, like User.extraInfo/Ticket.description) snapshot of what
    // changed: for CREATE the created field values, for UPDATE the {old,new} pairs of changed
    // fields, for DELETE the deleted row's field values. See ADR-0003.
    @Column(name = "details", updatable = false, columnDefinition = "text")
    private String details;

    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false, columnDefinition = "text")
    private String userAgent;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
