package com.unisage.backend.service.backup;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackupFrequencyTest {

    private static final LocalTime TWO_AM = LocalTime.of(2, 0);

    @Test
    void daily_beforeTodaysSlot_returnsYesterdays() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 1, 59);

        assertThat(BackupFrequency.DAILY.latestSlot(now, TWO_AM))
                .contains(LocalDateTime.of(2026, 10, 12, 2, 0));
    }

    @Test
    void daily_atTodaysSlot_returnsToday() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 2, 0);

        assertThat(BackupFrequency.DAILY.latestSlot(now, TWO_AM)).contains(now);
    }

    @Test
    void weekly_midweek_returnsLastSunday() {
        // 2026-10-13 is a Tuesday.
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 9, 0);

        assertThat(BackupFrequency.WEEKLY.latestSlot(now, TWO_AM))
                .contains(LocalDateTime.of(2026, 10, 11, 2, 0));
    }

    @Test
    void weekly_sundayBeforeSlot_returnsPreviousSunday() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 11, 1, 0);

        assertThat(BackupFrequency.WEEKLY.latestSlot(now, TWO_AM))
                .contains(LocalDateTime.of(2026, 10, 4, 2, 0));
    }

    @Test
    void monthly_firstOfMonthBeforeSlot_returnsPreviousMonth() {
        LocalDateTime now = LocalDateTime.of(2026, 3, 1, 0, 30);

        assertThat(BackupFrequency.MONTHLY.latestSlot(now, TWO_AM))
                .contains(LocalDateTime.of(2026, 2, 1, 2, 0));
    }

    @Test
    void yearly_returnsFirstOfJanuary() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 9, 0);

        assertThat(BackupFrequency.YEARLY.latestSlot(now, TWO_AM))
                .contains(LocalDateTime.of(2026, 1, 1, 2, 0));
    }

    @Test
    void off_hasNoSlot() {
        assertThat(BackupFrequency.OFF.latestSlot(LocalDateTime.of(2026, 10, 13, 9, 0), TWO_AM)).isEmpty();
    }

    @Test
    void parse_isCaseInsensitive_andRejectsUnknown() {
        assertThat(BackupFrequency.parse(" weekly ")).contains(BackupFrequency.WEEKLY);
        assertThat(BackupFrequency.parse("HOURLY")).isEmpty();
        assertThat(BackupFrequency.parse(null)).isEmpty();
    }
}
