package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.BudgetAction;
import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.UsagePurpose;

import lombok.Builder;

@Builder
public record BudgetResponse(
    UUID id,
    BudgetScope scope,
    String scopeProvider,
    UsagePurpose scopePurpose,
    BudgetPeriod period,
    BigDecimal limitUsd,
    BudgetAction action,
    Integer throttleMaxConcurrency,
    Boolean isEnabled,
    /** Real spend for the current period (plan.md "Budget semantics" committed definition) - null when {@code isEnabled} is false. */
    BigDecimal spentUsd,
    /** {@code spentUsd / limitUsd * 100}, null when {@code spentUsd} is null. */
    Double spentPercent,
    Boolean isActive,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    String updatedBy
) {}
