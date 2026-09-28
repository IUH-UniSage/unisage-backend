package com.unisage.backend.entity.enums;

/** Send lifecycle of one {@code BudgetAlertLog} row - plan.md "BudgetAlertLog", "Vòng đời gửi".
 * {@code GAVE_UP} = 3 failed attempts, no more retries; {@code SKIPPED} = channel not configured
 * (missing SMTP/Slack env), never retried. */
public enum AlertStatus {
    PENDING,
    SENT,
    FAILED,
    GAVE_UP,
    SKIPPED
}
