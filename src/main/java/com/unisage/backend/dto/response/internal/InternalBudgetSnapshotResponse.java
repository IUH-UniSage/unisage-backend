package com.unisage.backend.dto.response.internal;

import java.math.BigDecimal;
import java.util.List;

import com.unisage.backend.entity.enums.BudgetAction;
import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.UsagePurpose;

import lombok.Builder;

/** {@code GET /internal/budgets/snapshot} - plan.md "Internal API & bảo mật" endpoint #2. Only
 * {@code isEnabled} rows - a disabled budget has no effect and Python has no reason to know about
 * it. {@code version} is the same singleton Model Registry already bumps/publishes; Python reloads
 * this snapshot on the same poll/pub-sub cycle as the model registry snapshot. */
@Builder
public record InternalBudgetSnapshotResponse(long version, List<BudgetEntry> budgets) {

    @Builder
    public record BudgetEntry(
        BudgetScope scope,
        String scopeProvider,
        UsagePurpose scopePurpose,
        BudgetPeriod period,
        BigDecimal limitUsd,
        BudgetAction action,
        Integer throttleMaxConcurrency
    ) {}
}
