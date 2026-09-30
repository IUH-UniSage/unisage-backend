package com.unisage.backend.service.budget;

/**
 * Sends alerts already claimed by {@link BudgetAlertDetectionService} - IN_APP marks itself
 * SENT immediately, EMAIL/SLACK actually deliver. A row that fails to send gets retried with
 * exponential backoff until it gives up after 3 attempts.
 */
public interface BudgetAlertDispatchService {

    /** Locks and processes every alert row currently due, returns how many it processed. */
    int dispatchPending();
}
