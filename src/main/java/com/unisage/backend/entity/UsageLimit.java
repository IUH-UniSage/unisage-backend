package com.unisage.backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.UsageLimitScope;
import com.unisage.backend.entity.enums.UsageLimitType;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "usage_limits", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "limit_type", "scope", "scope_date"}),
    @UniqueConstraint(columnNames = {"ip_address", "limit_type", "scope", "scope_date"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class UsageLimit extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "ip_address")
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "limit_type")
    private UsageLimitType limitType;

    @Enumerated(EnumType.STRING)
    private UsageLimitScope scope;

    @Column(name = "scope_date")
    private LocalDate scopeDate;

    @Builder.Default
    @Column(name = "used_count")
    private Integer usedCount = 0;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
