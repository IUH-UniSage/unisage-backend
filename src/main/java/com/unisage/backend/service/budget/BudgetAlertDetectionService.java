package com.unisage.backend.service.budget;

/**
 * Detects budget-threshold and spend-spike conditions and claims one
 * {@code budget_alert_log} row per (condition, channel) - the actual send (Task 13) is a
 * separate, later step that only ever picks up rows already claimed here.
 */
public interface BudgetAlertDetectionService {

    /** Checks every enabled {@code Budget} against its current-period spend, claiming one row
     * per reached threshold per enabled channel. Returns how many rows this call newly claimed. */
    int checkThresholds();

    /** Checks yesterday's SYSTEM spend against the trailing 7-day average, claiming one row per
     * enabled channel if it breached {@code spikeThresholdPercent}. Returns how many rows this
     * call newly claimed (0 also when spike detection is disabled). */
    int checkSpike();
}
