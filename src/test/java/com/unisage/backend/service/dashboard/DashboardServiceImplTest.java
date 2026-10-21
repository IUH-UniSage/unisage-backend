package com.unisage.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.dto.response.BudgetResponse;
import com.unisage.backend.dto.response.DashboardSummaryResponse;
import com.unisage.backend.dto.response.DashboardSummaryResponse.ActivityBucket;
import com.unisage.backend.dto.response.DashboardSummaryResponse.AnswerWindow;
import com.unisage.backend.dto.response.DashboardSummaryResponse.AskerWindow;
import com.unisage.backend.dto.response.DashboardSummaryResponse.ModelCost;
import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.DocumentRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.service.budget.BudgetAlertLogService;
import com.unisage.backend.service.budget.BudgetService;
import com.unisage.backend.service.dashboard.DashboardMetricsQueries.CostTotals;
import com.unisage.backend.service.dashboard.DashboardMetricsQueries.ModelCostRow;
import com.unisage.backend.service.dashboard.DashboardMetricsQueries.PurposeCostRow;
import com.unisage.backend.service.health.SystemHealthCheckService;

class DashboardServiceImplTest {

    // 18:30 UTC on Oct 5 is already 01:30 on Oct 6 in Asia/Ho_Chi_Minh.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T18:30:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime FROM_UTC = LocalDateTime.of(2026, 10, 5, 17, 0);
    private static final LocalDateTime TO_UTC = LocalDateTime.of(2026, 10, 5, 18, 30);

    private final DashboardMetricsQueries metricsQueries = mock(DashboardMetricsQueries.class);
    private final BudgetService budgetService = mock(BudgetService.class);
    private final BudgetAlertLogService budgetAlertLogService = mock(BudgetAlertLogService.class);

    @BeforeEach
    void stubEmptyMetrics() {
        when(metricsQueries.answers(any(), any())).thenReturn(new AnswerWindow(0, 0, 0, null, null));
        when(metricsQueries.askers(any(), any())).thenReturn(new AskerWindow(0, 0));
        when(metricsQueries.costTotals(any(), any())).thenReturn(new CostTotals(BigDecimal.ZERO, BigDecimal.ZERO, 0));
        when(budgetAlertLogService.getActive()).thenReturn(List.of());
    }

    private DashboardServiceImpl service() {
        DashboardServiceImpl service = new DashboardServiceImpl(
                metricsQueries,
                mock(DocumentRepository.class),
                mock(TicketRepository.class),
                budgetService,
                budgetAlertLogService,
                mock(SystemHealthCheckService.class),
                CLOCK);
        ReflectionTestUtils.setField(service, "zoneId", ZoneId.of("Asia/Ho_Chi_Minh"));
        return service;
    }

    @Test
    void todayQueriesTheLocalDayInUtcAndYesterdayForComparison() {
        service().getSummary(DashboardPeriod.TODAY);

        verify(metricsQueries).answers(FROM_UTC, TO_UTC);
        verify(metricsQueries).answers(FROM_UTC.minusDays(1), TO_UTC.minusDays(1));
        verify(metricsQueries).answerBuckets(FROM_UTC, TO_UTC, "Asia/Ho_Chi_Minh", true);
    }

    @Test
    void activityIsZeroFilledAndJoinsAnswerAndCostBuckets() {
        LocalDateTime oneAm = LocalDateTime.of(2026, 10, 6, 1, 0);
        when(metricsQueries.answerBuckets(any(), any(), anyString(), anyBoolean()))
                .thenReturn(Map.of(oneAm, new long[] { 3, 1 }));
        when(metricsQueries.costBuckets(any(), any(), anyString(), anyBoolean()))
                .thenReturn(Map.of(oneAm, new BigDecimal("0.42")));

        List<ActivityBucket> activity = service().getSummary(DashboardPeriod.TODAY).activity();

        assertThat(activity).containsExactly(
                new ActivityBucket(LocalDateTime.of(2026, 10, 6, 0, 0), 0, 0, BigDecimal.ZERO),
                new ActivityBucket(oneAm, 3, 1, new BigDecimal("0.42")));
    }

    @Test
    void costCarriesPurposeBreakdownAndTheBudgetsClosestToTheirLimit() {
        when(metricsQueries.costByPurpose(any(), any()))
                .thenReturn(List.of(new PurposeCostRow("CHAT", new BigDecimal("1.50"), 12)));
        when(budgetService.getAll()).thenReturn(List.of(
                budget(40.0, true), budget(90.0, true), budget(99.0, false), budget(70.0, true),
                budget(10.0, true)));

        DashboardSummaryResponse.CostStats cost = service().getSummary(DashboardPeriod.LAST_7_DAYS).cost();

        assertThat(cost.byPurpose()).singleElement()
                .satisfies(row -> assertThat(row.costUsd()).isEqualByComparingTo("1.50"));
        assertThat(cost.budgets())
                .extracting(DashboardSummaryResponse.BudgetUsage::spentPercent)
                .containsExactly(90.0, 70.0, 40.0);
    }

    @Test
    void modelCostsCarryTheirOwnPreviousPeriodSpend() {
        when(metricsQueries.costByModel(any(), any())).thenReturn(List.of(
                new ModelCostRow("openai", "gpt-4o-mini", 10, 12, 2, 5000, 800,
                        new BigDecimal("0.30"), BigDecimal.ZERO, 1800L),
                new ModelCostRow("gemini", "gemini-2.5-flash", 3, 3, 0, 900, 100,
                        new BigDecimal("0.05"), BigDecimal.ZERO, 900L)));
        when(metricsQueries.pricedCostByModel(any(), any()))
                .thenReturn(Map.of("openai/gpt-4o-mini", new BigDecimal("0.20")));

        List<ModelCost> byModel = service().getSummary(DashboardPeriod.TODAY).cost().byModel();

        assertThat(byModel).extracting(ModelCost::modelName)
                .containsExactly("gpt-4o-mini", "gemini-2.5-flash");
        assertThat(byModel.get(0).previousCostUsd()).isEqualByComparingTo("0.20");
        assertThat(byModel.get(1).previousCostUsd()).isEqualByComparingTo("0");
    }

    @Test
    void customRangeIsQueriedFromLocalMidnightOfItsFirstDay() {
        service().getSummary(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10));

        // 00:00 Sep 1 (UTC+7) == 17:00 Aug 31 UTC; Sep 10 inclusive ends at 00:00 Sep 11 local.
        verify(metricsQueries).answers(
                LocalDateTime.of(2026, 8, 31, 17, 0), LocalDateTime.of(2026, 9, 10, 17, 0));
    }

    @Test
    void customRangeInTheFutureIsRejected() {
        assertThatThrownBy(() -> service().getSummary(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DASHBOARD_INVALID_RANGE);
    }

    private static BudgetResponse budget(double spentPercent, boolean enabled) {
        return BudgetResponse.builder()
                .id(UUID.randomUUID())
                .scope(BudgetScope.SYSTEM)
                .period(BudgetPeriod.DAILY)
                .limitUsd(new BigDecimal("10"))
                .spentUsd(BigDecimal.valueOf(spentPercent / 10))
                .spentPercent(spentPercent)
                .isEnabled(enabled)
                .isActive(true)
                .build();
    }
}
