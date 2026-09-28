package com.unisage.backend.service.usagelog;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.unisage.backend.entity.enums.BudgetPeriod;

/**
 * Calendar DAILY/MONTHLY period boundaries, cut in {@code app.timezone} then converted to UTC -
 * plan.md "Architecture Decisions": "Mọi kỳ DAILY/MONTHLY tính theo app.timezone", DB columns stay
 * UTC. Shared by Budget's "current period spend" (Task 3) and the internal period-totals endpoint
 * (Task 4) so the two never compute a period boundary two different ways.
 */
@Component
public class UsagePeriodCalculator {

    private final Clock clock;

    @Value("${app.timezone:Asia/Ho_Chi_Minh}")
    private ZoneId zoneId;

    public UsagePeriodCalculator(Clock clock) {
        this.clock = clock;
    }

    /** [start, end) of the current period, both in UTC, matching the DB's storage convention. */
    public PeriodBounds currentPeriodBoundsUtc(BudgetPeriod period) {
        ZonedDateTime nowZoned = ZonedDateTime.now(clock).withZoneSameInstant(zoneId);
        ZonedDateTime startZoned = switch (period) {
            case DAILY -> nowZoned.toLocalDate().atStartOfDay(zoneId);
            case MONTHLY -> nowZoned.toLocalDate().withDayOfMonth(1).atStartOfDay(zoneId);
        };
        ZonedDateTime endZoned = switch (period) {
            case DAILY -> startZoned.plusDays(1);
            case MONTHLY -> startZoned.plusMonths(1);
        };
        return new PeriodBounds(toUtc(startZoned), toUtc(endZoned));
    }

    private static LocalDateTime toUtc(ZonedDateTime value) {
        return value.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    public record PeriodBounds(LocalDateTime startUtc, LocalDateTime endUtc) {
    }
}
