package com.unisage.backend.entity.enums;

/** {@code THRESHOLD} = a {@code Budget}'s spend crossed one of {@code BudgetAlertSetting
 * .thresholdsPercent}; {@code SPIKE} = today's spend is unusually high vs the last 7 days,
 * independent of any budget. */
public enum AlertType {
    THRESHOLD,
    SPIKE
}
