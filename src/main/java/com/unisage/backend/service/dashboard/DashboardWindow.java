package com.unisage.backend.service.dashboard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * UNISAGE-105: the local-time cut of a {@link DashboardPeriod} plus its comparison window.
 * {@code from}/{@code to} are local wall-clock times in the app zone; {@link #fromUtc()} etc. are
 * what the UTC-stored columns are filtered on.
 */
public record DashboardWindow(
        DashboardPeriod period,
        ZoneId zoneId,
        LocalDateTime from,
        LocalDateTime to,
        LocalDateTime previousFrom,
        LocalDateTime previousTo) {

    /** A year (leap-safe) is the longest range a custom pick may span. */
    public static final int MAX_CUSTOM_DAYS = 366;

    public static DashboardWindow of(DashboardPeriod period, ZonedDateTime now, ZoneId zoneId) {
        LocalDateTime localNow = now.withZoneSameInstant(zoneId).toLocalDateTime();
        LocalDate today = localNow.toLocalDate();
        return switch (period) {
            case TODAY -> new DashboardWindow(period, zoneId, today.atStartOfDay(), localNow,
                    today.minusDays(1).atStartOfDay(), localNow.minusDays(1));
            case LAST_7_DAYS -> rolling(period, zoneId, today, localNow, 7);
            case LAST_30_DAYS -> rolling(period, zoneId, today, localNow, 30);
            case CUSTOM -> throw new IllegalArgumentException("CUSTOM needs a date range - use DashboardWindow.custom");
            case THIS_MONTH -> {
                LocalDateTime from = today.withDayOfMonth(1).atStartOfDay();
                // minusMonths clamps (Mar 31 -> Feb 28), so the comparison never overruns last month.
                yield new DashboardWindow(period, zoneId, from, localNow, from.minusMonths(1), localNow.minusMonths(1));
            }
        };
    }

    /**
     * {@code fromDate}..{@code toDate} inclusive. A range ending today stops at "now" so today's
     * half-finished day is compared with the same hours of the comparison window's last day.
     * Callers validate the range ({@link #isValidCustomRange}).
     */
    public static DashboardWindow custom(LocalDate fromDate, LocalDate toDate, ZonedDateTime now, ZoneId zoneId) {
        LocalDateTime localNow = now.withZoneSameInstant(zoneId).toLocalDateTime();
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime endOfRange = toDate.plusDays(1).atStartOfDay();
        LocalDateTime to = endOfRange.isAfter(localNow) ? localNow : endOfRange;
        long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        return new DashboardWindow(DashboardPeriod.CUSTOM, zoneId, from, to,
                from.minusDays(days), to.minusDays(days));
    }

    public static boolean isValidCustomRange(LocalDate fromDate, LocalDate toDate, LocalDate today) {
        return !fromDate.isAfter(toDate)
                && !toDate.isAfter(today)
                && ChronoUnit.DAYS.between(fromDate, toDate) < MAX_CUSTOM_DAYS;
    }

    private static DashboardWindow rolling(DashboardPeriod period, ZoneId zoneId, LocalDate today,
            LocalDateTime localNow, int days) {
        LocalDateTime from = today.minusDays(days - 1L).atStartOfDay();
        return new DashboardWindow(period, zoneId, from, localNow, from.minusDays(days), localNow.minusDays(days));
    }

    /** Hourly buckets for a window of at most one day (today, a single picked day), daily otherwise. */
    public boolean hourly() {
        return ChronoUnit.HOURS.between(from, to) <= 24;
    }

    /**
     * Start (local) of every chart bucket from {@link #from} up to the last instant before
     * {@link #to} - {@code to} is exclusive, so a past range ending at midnight gets no extra day.
     */
    public List<LocalDateTime> bucketStarts() {
        ChronoUnit unit = hourly() ? ChronoUnit.HOURS : ChronoUnit.DAYS;
        LocalDateTime last = to.isAfter(from) ? to.minusNanos(1).truncatedTo(unit) : from;
        List<LocalDateTime> starts = new ArrayList<>();
        for (LocalDateTime start = from; !start.isAfter(last); start = start.plus(1, unit)) {
            starts.add(start);
        }
        return starts;
    }

    public LocalDateTime fromUtc() {
        return toUtc(from);
    }

    public LocalDateTime toUtc() {
        return toUtc(to);
    }

    public LocalDateTime previousFromUtc() {
        return toUtc(previousFrom);
    }

    public LocalDateTime previousToUtc() {
        return toUtc(previousTo);
    }

    private LocalDateTime toUtc(LocalDateTime local) {
        return local.atZone(zoneId).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
