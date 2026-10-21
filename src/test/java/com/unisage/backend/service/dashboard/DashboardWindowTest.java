package com.unisage.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class DashboardWindowTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // 18:30 UTC on Oct 5 is already 01:30 on Oct 6 in Asia/Ho_Chi_Minh.
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-05T18:30:00Z");

    @Test
    void todayRunsFromLocalMidnightAndComparesWithYesterdayUpToTheSameTime() {
        DashboardWindow window = DashboardWindow.of(DashboardPeriod.TODAY, NOW, ZONE);

        assertThat(window.from()).isEqualTo(LocalDateTime.of(2026, 10, 6, 0, 0));
        assertThat(window.to()).isEqualTo(LocalDateTime.of(2026, 10, 6, 1, 30));
        assertThat(window.previousFrom()).isEqualTo(LocalDateTime.of(2026, 10, 5, 0, 0));
        assertThat(window.previousTo()).isEqualTo(LocalDateTime.of(2026, 10, 5, 1, 30));
        // 00:00 Oct 6 (UTC+7) == 17:00 Oct 5 UTC.
        assertThat(window.fromUtc()).isEqualTo(LocalDateTime.of(2026, 10, 5, 17, 0));
        assertThat(window.toUtc()).isEqualTo(LocalDateTime.of(2026, 10, 5, 18, 30));
        assertThat(window.hourly()).isTrue();
        assertThat(window.bucketStarts()).containsExactly(
                LocalDateTime.of(2026, 10, 6, 0, 0), LocalDateTime.of(2026, 10, 6, 1, 0));
    }

    @Test
    void lastSevenDaysIncludesTodayAndComparesWithTheSevenDaysBefore() {
        DashboardWindow window = DashboardWindow.of(DashboardPeriod.LAST_7_DAYS, NOW, ZONE);

        assertThat(window.from()).isEqualTo(LocalDateTime.of(2026, 9, 30, 0, 0));
        assertThat(window.previousFrom()).isEqualTo(LocalDateTime.of(2026, 9, 23, 0, 0));
        assertThat(window.previousTo()).isEqualTo(LocalDateTime.of(2026, 9, 29, 1, 30));
        List<LocalDateTime> buckets = window.bucketStarts();
        assertThat(buckets).hasSize(7);
        assertThat(buckets.get(buckets.size() - 1)).isEqualTo(LocalDateTime.of(2026, 10, 6, 0, 0));
    }

    @Test
    void customPastRangeIsWholeDaysComparedWithTheSameNumberOfDaysBefore() {
        DashboardWindow window = DashboardWindow.custom(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10), NOW, ZONE);

        assertThat(window.from()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(window.to()).isEqualTo(LocalDateTime.of(2026, 9, 11, 0, 0));
        assertThat(window.previousFrom()).isEqualTo(LocalDateTime.of(2026, 8, 22, 0, 0));
        assertThat(window.previousTo()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        // "to" is exclusive: Sep 1..10 is ten daily buckets, not eleven.
        List<LocalDateTime> buckets = window.bucketStarts();
        assertThat(buckets).hasSize(10);
        assertThat(buckets.get(9)).isEqualTo(LocalDateTime.of(2026, 9, 10, 0, 0));
    }

    @Test
    void customSingleDayIsHourlyAndARangeEndingTodayStopsAtNow() {
        DashboardWindow pastDay = DashboardWindow.custom(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), NOW, ZONE);
        assertThat(pastDay.hourly()).isTrue();
        assertThat(pastDay.bucketStarts()).hasSize(24);

        DashboardWindow endingToday = DashboardWindow.custom(
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), NOW, ZONE);
        assertThat(endingToday.to()).isEqualTo(LocalDateTime.of(2026, 10, 6, 1, 30));
        assertThat(endingToday.previousTo()).isEqualTo(LocalDateTime.of(2026, 10, 4, 1, 30));
    }

    @Test
    void customRangeMustBeOrderedNotInTheFutureAndAtMostAYear() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        assertThat(DashboardWindow.isValidCustomRange(today.minusDays(365), today, today)).isTrue();
        assertThat(DashboardWindow.isValidCustomRange(today.minusDays(366), today, today)).isFalse();
        assertThat(DashboardWindow.isValidCustomRange(today, today.minusDays(1), today)).isFalse();
        assertThat(DashboardWindow.isValidCustomRange(today, today.plusDays(1), today)).isFalse();
    }

    @Test
    void thisMonthComparesWithLastMonthClampedToItsLength() {
        ZonedDateTime endOfMarch = ZonedDateTime.of(2026, 3, 31, 10, 0, 0, 0, ZONE);

        DashboardWindow window = DashboardWindow.of(DashboardPeriod.THIS_MONTH, endOfMarch, ZONE);

        assertThat(window.from()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(window.previousFrom()).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
        assertThat(window.previousTo()).isEqualTo(LocalDateTime.of(2026, 2, 28, 10, 0));
        assertThat(window.bucketStarts()).hasSize(31);
    }
}
