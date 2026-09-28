package com.unisage.backend.entity;

import java.math.BigDecimal;
import java.util.UUID;

import com.unisage.backend.entity.enums.BudgetAction;
import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.UsagePurpose;

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
import lombok.experimental.SuperBuilder;

/**
 * A spend limit for one {@code period}, scoped to the whole system, one provider, or one
 * {@link UsagePurpose} - plan.md "Budget". Exactly one of {@link #scopeProvider}/
 * {@link #scopePurpose} is set, matched to {@link #scope} by a DB {@code CHECK} (V26) - the service
 * validates the same rule before hitting the DB so the error is a clean 400, not a constraint
 * violation. At most one enabled row per (scope[, provider/purpose], period) - enforced by 3
 * partial unique indexes (V26), not by application code alone, because PostgreSQL's plain unique
 * index treats every {@code NULL} as distinct and would let two SYSTEM budgets for the same period
 * both exist.
 */
@Entity
@Table(name = "budgets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class Budget extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", columnDefinition = "varchar(20)", nullable = false)
    private BudgetScope scope;

    /** Required iff {@link #scope} = PROVIDER. Compared lowercase against {@code ChatModel.provider}. */
    @Column(name = "scope_provider")
    private String scopeProvider;

    /** Required iff {@link #scope} = PURPOSE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_purpose", columnDefinition = "varchar(20)")
    private UsagePurpose scopePurpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", columnDefinition = "varchar(20)", nullable = false)
    private BudgetPeriod period;

    @Column(name = "limit_usd", nullable = false, precision = 18, scale = 8)
    private BigDecimal limitUsd;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", columnDefinition = "varchar(20)", nullable = false)
    private BudgetAction action;

    /** Required iff {@link #action} = THROTTLE, and must be > 0; NULL for ALERT/BLOCK. */
    @Column(name = "throttle_max_concurrency")
    private Integer throttleMaxConcurrency;

    @Builder.Default
    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled = true;
}
