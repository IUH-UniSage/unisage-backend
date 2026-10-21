package com.unisage.backend.service.dashboard;

/**
 * UNISAGE-105: the dashboard's period switcher. Every period ends "now" and is compared with the
 * same-length slice right before it, so a half-finished day/month is never compared with a whole one.
 */
public enum DashboardPeriod {
    /** Local midnight to now, hourly buckets; compared with yesterday up to the same time. */
    TODAY,
    /** The last 7 local days including today, daily buckets. */
    LAST_7_DAYS,
    /** The last 30 local days including today, daily buckets. */
    LAST_30_DAYS,
    /** The 1st of this month to now, daily buckets; compared with last month up to the same day/time. */
    THIS_MONTH,
    /** An admin-picked {@code from}..{@code to} local date range (inclusive); compared with the
     * same number of days right before it. Hourly buckets when it is a single day. */
    CUSTOM
}
