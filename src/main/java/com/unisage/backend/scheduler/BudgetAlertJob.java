package com.unisage.backend.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unisage.backend.service.budget.BudgetAlertDetectionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Claims threshold and spike alerts on a schedule - the actual send (dispatch to
 * IN_APP/EMAIL/SLACK) is a separate later step that only ever picks up rows already
 * PENDING/FAILED here. Both methods only ever INSERT via
 * {@code BudgetAlertLogRepository.claim()}'s {@code ON CONFLICT DO NOTHING}, so running on more
 * than one instance at once is safe - whichever call wins the insert is the one that counts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BudgetAlertJob {

    private final BudgetAlertDetectionService detectionService;

    @Scheduled(cron = "${app.budget-alert.check-cron:0 */2 * * * *}")
    public void checkThresholds() {
        detectionService.checkThresholds();
    }

    @Scheduled(cron = "${app.budget-alert.spike-cron:0 5 0 * * *}", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void checkSpike() {
        detectionService.checkSpike();
    }
}
