package com.unisage.backend.service.budget;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.unisage.backend.dto.request.BudgetRequest;
import com.unisage.backend.dto.request.internal.UsageLineIngestRequest;
import com.unisage.backend.dto.request.internal.UsageLogIngestRequest;
import com.unisage.backend.entity.BudgetAlertSetting;
import com.unisage.backend.entity.enums.BudgetAction;
import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;
import com.unisage.backend.repository.BudgetAlertSettingRepository;
import com.unisage.backend.repository.BudgetRepository;
import com.unisage.backend.support.PostgresIntegrationTest;
import com.unisage.backend.service.usagelog.RequestUsageLogService;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetAlertDetectionServiceImplTest extends PostgresIntegrationTest {

    @Autowired
    private BudgetAlertDetectionService detectionService;

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private BudgetAlertSettingRepository settingRepository;

    @Autowired
    private RequestUsageLogService requestUsageLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM budget_alert_log");
        budgetRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM request_usage_lines");
        jdbcTemplate.update("DELETE FROM request_usage_logs");
        BudgetAlertSetting defaults = settingRepository.findById((short) 1).orElseThrow();
        defaults.setThresholdsPercent(new Integer[] {50, 80, 100});
        defaults.setSpikeDetectionEnabled(false);
        defaults.setSpikeThresholdPercent(50);
        defaults.setInAppEnabled(true);
        defaults.setEmailEnabled(false);
        defaults.setSlackEnabled(false);
        settingRepository.save(defaults);
    }

    private void seedSpend(OffsetDateTime when, BigDecimal costUsd) {
        requestUsageLogService.ingest(new UsageLogIngestRequest(
                UUID.randomUUID(), UsagePurpose.CHAT, null, null, null, null, null,
                UsageRequestStatus.SUCCESS, when, when, List.of(
                        new UsageLineIngestRequest(0, "n", 0, null, "openai", "gpt-4o-mini",
                                ChatModelSourceType.CLOUD_API, 100, 50, 0,
                                costUsd, costUsd, UsageCostStatus.PRICED, 10,
                                UsageRequestStatus.SUCCESS, null, when))));
    }

    @Test
    void thresholdBreach_claimsOneAlertPerReachedThresholdAndChannel() {
        var budget = budgetService.create(BudgetRequest.builder()
                .scope(BudgetScope.SYSTEM)
                .period(BudgetPeriod.DAILY)
                .limitUsd(BigDecimal.valueOf(10))
                .action(BudgetAction.ALERT)
                .build());
        // 85% of a $10 budget -> crosses 50 and 80, not 100.
        seedSpend(OffsetDateTime.now(), BigDecimal.valueOf(8.5));

        int claimed = detectionService.checkThresholds();

        assertThat(claimed).isEqualTo(2);
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM budget_alert_log WHERE budget_id = ?", Long.class, budget.id());
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void thresholdBreach_secondRunDoesNotReclaimTheSameThreshold() {
        budgetService.create(BudgetRequest.builder()
                .scope(BudgetScope.SYSTEM)
                .period(BudgetPeriod.DAILY)
                .limitUsd(BigDecimal.valueOf(10))
                .action(BudgetAction.ALERT)
                .build());
        seedSpend(OffsetDateTime.now(), BigDecimal.valueOf(8.5));

        int firstRun = detectionService.checkThresholds();
        int secondRun = detectionService.checkThresholds();

        assertThat(firstRun).isEqualTo(2);
        assertThat(secondRun).isEqualTo(0);
    }

    @Test
    void thresholdBreach_twoConcurrentRunsClaimEachAlertExactlyOnce() throws InterruptedException {
        budgetService.create(BudgetRequest.builder()
                .scope(BudgetScope.SYSTEM)
                .period(BudgetPeriod.DAILY)
                .limitUsd(BigDecimal.valueOf(10))
                .action(BudgetAction.ALERT)
                .build());
        seedSpend(OffsetDateTime.now(), BigDecimal.valueOf(8.5));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger totalClaimed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    totalClaimed.addAndGet(detectionService.checkThresholds());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // Exactly 2 thresholds (50, 80) claimed across both runs combined, never double-claimed.
        assertThat(totalClaimed.get()).isEqualTo(2);
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM budget_alert_log", Long.class);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void addingANewThresholdMidPeriod_onlySendsTheNewOne() {
        budgetService.create(BudgetRequest.builder()
                .scope(BudgetScope.SYSTEM)
                .period(BudgetPeriod.DAILY)
                .limitUsd(BigDecimal.valueOf(10))
                .action(BudgetAction.ALERT)
                .build());
        seedSpend(OffsetDateTime.now(), BigDecimal.valueOf(8.5));
        detectionService.checkThresholds();

        BudgetAlertSetting setting = settingRepository.findById((short) 1).orElseThrow();
        setting.setThresholdsPercent(new Integer[] {50, 80, 85});
        settingRepository.save(setting);

        int secondRun = detectionService.checkThresholds();

        assertThat(secondRun).isEqualTo(1);
    }

    @Test
    void noEnabledChannels_claimsNothing() {
        budgetService.create(BudgetRequest.builder()
                .scope(BudgetScope.SYSTEM)
                .period(BudgetPeriod.DAILY)
                .limitUsd(BigDecimal.valueOf(10))
                .action(BudgetAction.ALERT)
                .build());
        seedSpend(OffsetDateTime.now(), BigDecimal.valueOf(8.5));
        BudgetAlertSetting setting = settingRepository.findById((short) 1).orElseThrow();
        setting.setInAppEnabled(false);
        settingRepository.save(setting);

        assertThat(detectionService.checkThresholds()).isZero();
    }

    @Test
    void spikeDisabled_neverClaims() {
        assertThat(detectionService.checkSpike()).isZero();
    }

    @Test
    void spike_claimsWhenYesterdayFarExceedsTrailingWeekAverage() {
        BudgetAlertSetting setting = settingRepository.findById((short) 1).orElseThrow();
        setting.setSpikeDetectionEnabled(true);
        setting.setSpikeThresholdPercent(50);
        settingRepository.save(setting);

        OffsetDateTime now = OffsetDateTime.now();
        for (int daysAgo = 2; daysAgo <= 8; daysAgo++) {
            seedSpend(now.minusDays(daysAgo), BigDecimal.valueOf(1));
        }
        // Yesterday spent $10 vs a ~$1/day trailing average - a clear spike.
        seedSpend(now.minusDays(1), BigDecimal.valueOf(10));

        int claimed = detectionService.checkSpike();

        assertThat(claimed).isEqualTo(1);
    }

    @Test
    void spike_secondRunDoesNotReclaimTheSameDay() {
        BudgetAlertSetting setting = settingRepository.findById((short) 1).orElseThrow();
        setting.setSpikeDetectionEnabled(true);
        settingRepository.save(setting);

        OffsetDateTime now = OffsetDateTime.now();
        seedSpend(now.minusDays(1), BigDecimal.valueOf(10));

        int firstRun = detectionService.checkSpike();
        int secondRun = detectionService.checkSpike();

        assertThat(firstRun).isEqualTo(1);
        assertThat(secondRun).isZero();
    }
}
