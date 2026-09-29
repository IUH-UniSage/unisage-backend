package com.unisage.backend.service.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.entity.Budget;
import com.unisage.backend.entity.BudgetAlertSetting;
import com.unisage.backend.entity.enums.AlertChannel;
import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.repository.BudgetAlertLogRepository;
import com.unisage.backend.repository.BudgetAlertSettingRepository;
import com.unisage.backend.repository.BudgetRepository;
import com.unisage.backend.service.usagelog.UsagePeriodCalculator;
import com.unisage.backend.service.usagelog.UsagePeriodCalculator.PeriodBounds;
import com.unisage.backend.service.usagelog.UsagePeriodTotalsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetAlertDetectionServiceImpl implements BudgetAlertDetectionService {

    private static final short SINGLETON_ID = 1;
    private static final int TRAILING_WINDOW_DAYS = 7;

    private final BudgetRepository budgetRepository;
    private final BudgetAlertSettingRepository settingRepository;
    private final BudgetAlertLogRepository alertLogRepository;
    private final UsagePeriodCalculator periodCalculator;
    private final UsagePeriodTotalsService periodTotalsService;
    private final Clock clock;

    @Value("${app.timezone:Asia/Ho_Chi_Minh}")
    private ZoneId zoneId;

    @Override
    @Transactional
    public int checkThresholds() {
        BudgetAlertSetting setting = settingRepository.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "budget_alert_settings singleton row is missing"));
        List<AlertChannel> enabledChannels = enabledChannels(setting);
        if (enabledChannels.isEmpty()) {
            return 0;
        }

        int claimed = 0;
        for (Budget budget : budgetRepository.findByIsEnabledTrueAndIsActiveTrue()) {
            PeriodBounds bounds = periodCalculator.currentPeriodBoundsUtc(budget.getPeriod());
            BigDecimal spent = periodTotalsService.spentUsd(budget.getScope(), budget.getScopeProvider(),
                    budget.getScopePurpose(), bounds.startUtc(), bounds.endUtc());
            int percent = percentOf(spent, budget.getLimitUsd());
            LocalDate periodStart = bounds.startUtc().toLocalDate();

            for (Integer threshold : setting.getThresholdsPercent()) {
                if (percent < threshold) {
                    continue;
                }
                for (AlertChannel channel : enabledChannels) {
                    String dedupeKey = "THRESHOLD:%s:%s:%d:%s"
                            .formatted(budget.getId(), periodStart, threshold, channel);
                    int inserted = alertLogRepository.claim(UUID.randomUUID(), "THRESHOLD", budget.getId(),
                            periodStart, threshold, channel.name(), dedupeKey, spent, budget.getLimitUsd());
                    claimed += inserted;
                }
            }
        }
        if (claimed > 0) {
            log.info("checkThresholds: claimed {} new threshold alert(s)", claimed);
        }
        return claimed;
    }

    @Override
    @Transactional
    public int checkSpike() {
        BudgetAlertSetting setting = settingRepository.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "budget_alert_settings singleton row is missing"));
        if (!Boolean.TRUE.equals(setting.getSpikeDetectionEnabled())) {
            return 0;
        }
        List<AlertChannel> enabledChannels = enabledChannels(setting);
        if (enabledChannels.isEmpty()) {
            return 0;
        }

        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        LocalDate yesterday = today.minusDays(1);
        BigDecimal yesterdaySpend = spendOnDay(yesterday);

        List<BigDecimal> priorWeek = new ArrayList<>();
        for (int i = 1; i <= TRAILING_WINDOW_DAYS; i++) {
            priorWeek.add(spendOnDay(yesterday.minusDays(i)));
        }
        BigDecimal average = priorWeek.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(TRAILING_WINDOW_DAYS), 8, RoundingMode.HALF_UP);

        boolean isSpike;
        if (average.compareTo(BigDecimal.ZERO) <= 0) {
            // No spend at all in the trailing week - any spend yesterday is an infinite
            // percent increase, not just a number this formula can express cleanly.
            isSpike = yesterdaySpend.compareTo(BigDecimal.ZERO) > 0;
        } else {
            BigDecimal increasePercent = yesterdaySpend.subtract(average)
                    .divide(average, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            isSpike = increasePercent.compareTo(BigDecimal.valueOf(setting.getSpikeThresholdPercent())) >= 0;
        }
        if (!isSpike) {
            return 0;
        }

        int claimed = 0;
        for (AlertChannel channel : enabledChannels) {
            String dedupeKey = "SPIKE:%s:%s".formatted(yesterday, channel);
            claimed += alertLogRepository.claim(UUID.randomUUID(), "SPIKE", null, yesterday, null,
                    channel.name(), dedupeKey, yesterdaySpend, null);
        }
        if (claimed > 0) {
            log.info("checkSpike: yesterday={} spend={} vs 7-day avg={} - claimed {} new spike alert(s)",
                    yesterday, yesterdaySpend, average, claimed);
        }
        return claimed;
    }

    private BigDecimal spendOnDay(LocalDate day) {
        PeriodBounds bounds = periodCalculator.periodBoundsUtc(BudgetPeriod.DAILY, day.toString());
        return periodTotalsService.spentUsd(BudgetScope.SYSTEM, null, null, bounds.startUtc(), bounds.endUtc());
    }

    private static int percentOf(BigDecimal spent, BigDecimal limit) {
        if (limit.compareTo(BigDecimal.ZERO) <= 0) {
            return spent.compareTo(BigDecimal.ZERO) > 0 ? Integer.MAX_VALUE : 0;
        }
        return spent.divide(limit, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .intValue();
    }

    private static List<AlertChannel> enabledChannels(BudgetAlertSetting setting) {
        List<AlertChannel> channels = new ArrayList<>();
        if (Boolean.TRUE.equals(setting.getInAppEnabled())) {
            channels.add(AlertChannel.IN_APP);
        }
        if (Boolean.TRUE.equals(setting.getEmailEnabled())) {
            channels.add(AlertChannel.EMAIL);
        }
        if (Boolean.TRUE.equals(setting.getSlackEnabled())) {
            channels.add(AlertChannel.SLACK);
        }
        return channels;
    }
}
