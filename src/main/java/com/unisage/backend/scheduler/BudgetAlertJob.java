package com.unisage.backend.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unisage.backend.service.budget.BudgetAlertDetectionService;
import com.unisage.backend.service.budget.BudgetAlertDispatchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Claims threshold/spike alerts and dispatches whatever is due to send, both on a schedule.
 * Claiming only ever INSERTs via {@code BudgetAlertLogRepository.claim()}'s
 * {@code ON CONFLICT DO NOTHING}, and dispatch only ever locks rows via
 * {@code claimReadyToSend()}'s {@code FOR UPDATE SKIP LOCKED} - both safe to run on more than one
 * instance at once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BudgetAlertJob {

    private final BudgetAlertDetectionService detectionService;
    private final BudgetAlertDispatchService dispatchService;

    @Scheduled(cron = "${app.budget-alert.check-cron:0 */2 * * * *}")
    public void checkThresholds() {
        detectionService.checkThresholds();
    }

    @Scheduled(cron = "${app.budget-alert.spike-cron:0 5 0 * * *}", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void checkSpike() {
        detectionService.checkSpike();
    }

    @Scheduled(cron = "${app.budget-alert.check-cron:0 */2 * * * *}")
    public void dispatchPendingAlerts() {
        dispatchService.dispatchPending();
    }
}
