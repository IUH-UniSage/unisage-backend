package com.unisage.backend.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Identifies a guest (unauthenticated) browser across requests via a hashed, high-entropy token
 * delivered as an httpOnly cookie. See docs/adr for the guest-session TTL policy this backs.
 */
@Entity
@Table(name = "guest_sessions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class GuestSession extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
