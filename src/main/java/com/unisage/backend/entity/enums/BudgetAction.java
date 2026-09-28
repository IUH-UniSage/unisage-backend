package com.unisage.backend.entity.enums;

/** What happens when a {@code Budget}'s spend crosses its limit - plan.md "Budget semantics",
 * "Ma trận hành vi khi vượt limit". */
public enum BudgetAction {
    ALERT,
    THROTTLE,
    BLOCK
}
