package com.unisage.backend.service.backup;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/**
 * How often a scheduled backup runs (UNISAGE-102). Each frequency has one fixed slot per period
 * at the configured time of day: every day, every Sunday, the 1st of every month, or 1 January.
 */
public enum BackupFrequency {
    OFF,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY;

    /**
     * The most recent slot at or before {@code now}, or empty when {@link #OFF}. A run is due when
     * nothing has run for this slot yet, so a backend that was down at slot time catches up as
     * soon as it is back, instead of waiting a whole period.
     */
    public Optional<LocalDateTime> latestSlot(LocalDateTime now, LocalTime at) {
        if (this == OFF) {
            return Optional.empty();
        }
        LocalDate day = now.toLocalDate();
        LocalDate periodStart = switch (this) {
            case DAILY -> day;
            case WEEKLY -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
            case MONTHLY -> day.withDayOfMonth(1);
            case YEARLY -> day.withDayOfYear(1);
            case OFF -> throw new IllegalStateException();
        };
        LocalDateTime slot = periodStart.atTime(at);
        return Optional.of(slot.isAfter(now) ? previousPeriod(periodStart).atTime(at) : slot);
    }

    private LocalDate previousPeriod(LocalDate periodStart) {
        return switch (this) {
            case DAILY -> periodStart.minusDays(1);
            case WEEKLY -> periodStart.minusWeeks(1);
            case MONTHLY -> periodStart.minusMonths(1);
            case YEARLY -> periodStart.minusYears(1);
            case OFF -> throw new IllegalStateException();
        };
    }

    /** Case-insensitive, so "weekly" typed on the Settings page works too. */
    public static Optional<BackupFrequency> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
