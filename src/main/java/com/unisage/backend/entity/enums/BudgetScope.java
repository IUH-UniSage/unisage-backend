package com.unisage.backend.entity.enums;

/** What a {@code Budget} row limits spend for. See plan.md "Budget" for the CHECK constraint that
 * ties this to which of {@code scopeProvider}/{@code scopePurpose} must be set. */
public enum BudgetScope {
    SYSTEM,
    PROVIDER,
    PURPOSE
}
