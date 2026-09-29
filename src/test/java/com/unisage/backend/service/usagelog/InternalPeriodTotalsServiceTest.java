package com.unisage.backend.service.usagelog;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.unisage.backend.dto.request.internal.UsageLineIngestRequest;
import com.unisage.backend.dto.request.internal.UsageLogIngestRequest;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 4 verification: micro-USD period totals match the "PRICED->costUsd, UNPRICED->
 * estimatedCostUsd, FREE excluded" rule Python's committed counter uses. */
class InternalPeriodTotalsServiceTest extends PostgresIntegrationTest {

    @Autowired
    private RequestUsageLogService requestUsageLogService;

    @Autowired
    private InternalPeriodTotalsService internalPeriodTotalsService;

    @Autowired
    private UsagePeriodCalculator usagePeriodCalculator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM request_usage_lines");
        jdbcTemplate.update("DELETE FROM request_usage_logs");
    }

    @Test
    void pricedAndUnpricedLinesAreCountedInMicroUsd_freeIsExcluded() {
        OffsetDateTime now = OffsetDateTime.now();
        requestUsageLogService.ingest(new UsageLogIngestRequest(
                UUID.randomUUID(), UsagePurpose.CHAT, null, null, null, null, null, null,
                UsageRequestStatus.SUCCESS, now, now, List.of(
                        new UsageLineIngestRequest(0, "n", 0, null, "openai", "gpt-4o-mini",
                                ChatModelSourceType.CLOUD_API, 100, 50, 0,
                                BigDecimal.valueOf(0.000015), BigDecimal.valueOf(0.000015), UsageCostStatus.PRICED,
                                10, UsageRequestStatus.SUCCESS, null, now),
                        new UsageLineIngestRequest(1, "n", 0, null, "openai", "unknown-model",
                                ChatModelSourceType.CLOUD_API, 100, 50, 0,
                                null, BigDecimal.valueOf(0.05), UsageCostStatus.UNPRICED,
                                10, UsageRequestStatus.SUCCESS, null, now),
                        new UsageLineIngestRequest(2, "n", 0, null, null, "local-llama",
                                ChatModelSourceType.SELF_HOSTED, 100, 50, 0,
                                null, BigDecimal.ZERO, UsageCostStatus.FREE,
                                10, UsageRequestStatus.SUCCESS, null, now))));

        var bounds = usagePeriodCalculator.currentPeriodBoundsUtc(com.unisage.backend.entity.enums.BudgetPeriod.DAILY);
        var totals = internalPeriodTotalsService.periodTotalsMicroUsd(bounds.startUtc(), bounds.endUtc());

        // 0.000015 + 0.05 = 0.000015 USD (15 micro) + 50000 micro = 50015 micro-USD; FREE line contributes 0.
        assertThat(totals.get("SYSTEM")).isEqualTo(50_015L);
        assertThat(totals.get("PURPOSE:CHAT")).isEqualTo(50_015L);
        assertThat(totals.get("PURPOSE:EMBEDDING")).isEqualTo(0L);
    }
}
