package com.unisage.backend.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.UsageLimitWindow;

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
 * Running token counter of one identity (exactly one of user / guest session) for one window type.
 * {@code windowStart} is UTC; null means no window has started yet.
 */
@Entity
@Table(name = "usage_limits")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class UsageLimit extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_session_id")
    private GuestSession guestSession;

    @Enumerated(EnumType.STRING)
    @Column(name = "window_type", nullable = false)
    private UsageLimitWindow windowType;

    @Column(name = "window_start")
    private LocalDateTime windowStart;

    @Builder.Default
    @Column(name = "used_tokens", nullable = false)
    private Long usedTokens = 0L;
}
