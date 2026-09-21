package com.unisage.backend.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A named token quota (daily + weekly). Roles point at a plan; guests and roles without a plan use
 * the single plan flagged {@code isDefault}. A null limit means unlimited for that window.
 */
@Entity
@Table(name = "usage_limit_plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class UsageLimitPlan extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "daily_token_limit")
    private Long dailyTokenLimit;

    @Column(name = "weekly_token_limit")
    private Long weeklyTokenLimit;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;
}
