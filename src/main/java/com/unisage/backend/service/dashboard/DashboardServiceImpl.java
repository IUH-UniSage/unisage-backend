package com.unisage.backend.service.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.response.BudgetResponse;
import com.unisage.backend.dto.response.DashboardSummaryResponse;
import com.unisage.backend.dto.response.DashboardSummaryResponse.ActivityBucket;
import com.unisage.backend.dto.response.DashboardSummaryResponse.Attention;
import com.unisage.backend.dto.response.DashboardSummaryResponse.BudgetUsage;
import com.unisage.backend.dto.response.DashboardSummaryResponse.Comparison;
import com.unisage.backend.dto.response.DashboardSummaryResponse.CostStats;
import com.unisage.backend.dto.response.DashboardSummaryResponse.DocumentStats;
import com.unisage.backend.dto.response.DashboardSummaryResponse.ModelCost;
import com.unisage.backend.dto.response.DashboardSummaryResponse.PeriodInfo;
import com.unisage.backend.dto.response.DashboardSummaryResponse.PurposeCost;
import com.unisage.backend.entity.enums.DocStatus;
import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.DocumentRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.service.budget.BudgetAlertLogService;
import com.unisage.backend.service.budget.BudgetService;
import com.unisage.backend.service.dashboard.DashboardMetricsQueries.CostTotals;
import com.unisage.backend.service.health.SystemHealthCheckService;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-72/105. Every tile is a direct count/aggregate over data this backend already owns
 * (messages, usage logs, budgets, documents, tickets) plus the live health check
 * ({@link SystemHealthCheckService#checkNow()}) - nothing is cached, so the dashboard is current as
 * of the request.
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** The cost card only has room for the budgets closest to their limit. */
    private static final int TOP_BUDGETS = 3;

    private final DashboardMetricsQueries metricsQueries;
    private final DocumentRepository documentRepository;
    private final TicketRepository ticketRepository;
    private final BudgetService budgetService;
    private final BudgetAlertLogService budgetAlertLogService;
    private final SystemHealthCheckService systemHealthCheckService;
    private final Clock clock;

    // Periods are cut on calendar hours/days in app.timezone; the DB columns they filter are UTC.
    @Value("${app.timezone:Asia/Ho_Chi_Minh}")
    private ZoneId zoneId;

    @Override
    public DashboardSummaryResponse getSummary(DashboardPeriod period) {
        return buildSummary(DashboardWindow.of(period, ZonedDateTime.now(clock), zoneId));
    }

    @Override
    public DashboardSummaryResponse getSummary(LocalDate fromDate, LocalDate toDate) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.withZoneSameInstant(zoneId).toLocalDate();
        if (!DashboardWindow.isValidCustomRange(fromDate, toDate, today)) {
            throw new AppException(ErrorCode.DASHBOARD_INVALID_RANGE);
        }
        return buildSummary(DashboardWindow.custom(fromDate, toDate, now, zoneId));
    }

    private DashboardSummaryResponse buildSummary(DashboardWindow window) {

        return DashboardSummaryResponse.builder()
                .period(buildPeriodInfo(window))
                .attention(buildAttention())
                .answers(new Comparison<>(
                        metricsQueries.answers(window.fromUtc(), window.toUtc()),
                        metricsQueries.answers(window.previousFromUtc(), window.previousToUtc())))
                .askers(new Comparison<>(
                        metricsQueries.askers(window.fromUtc(), window.toUtc()),
                        metricsQueries.askers(window.previousFromUtc(), window.previousToUtc())))
                .activity(buildActivity(window))
                .cost(buildCost(window))
                .documents(buildDocumentStats())
                .health(systemHealthCheckService.checkNow())
                .build();
    }

    private PeriodInfo buildPeriodInfo(DashboardWindow window) {
        return PeriodInfo.builder()
                .key(window.period())
                .from(window.from())
                .to(window.to())
                .previousFrom(window.previousFrom())
                .previousTo(window.previousTo())
                .granularity(window.hourly() ? "HOUR" : "DAY")
                .build();
    }

    private Attention buildAttention() {
        return Attention.builder()
                .activeBudgetAlerts(budgetAlertLogService.getActive().size())
                .failedDocuments(documentRepository.countByStatusAndDeletedAtIsNull(DocStatus.FAILED))
                .outdatedDocuments(documentRepository.countByStatusAndDeletedAtIsNull(DocStatus.OUTDATED))
                .openTickets(ticketRepository.countByStatus(TicketStatus.OPEN))
                .build();
    }

    /** One bucket per hour/day of the window, zero-filled so the chart axis never has gaps. */
    private List<ActivityBucket> buildActivity(DashboardWindow window) {
        String zone = zoneId.getId();
        Map<LocalDateTime, long[]> answers = metricsQueries.answerBuckets(
                window.fromUtc(), window.toUtc(), zone, window.hourly());
        Map<LocalDateTime, BigDecimal> costs = metricsQueries.costBuckets(
                window.fromUtc(), window.toUtc(), zone, window.hourly());

        return window.bucketStarts().stream()
                .map(start -> {
                    long[] counts = answers.getOrDefault(start, new long[] { 0, 0 });
                    return ActivityBucket.builder()
                            .start(start)
                            .answered(counts[0])
                            .failed(counts[1])
                            .costUsd(costs.getOrDefault(start, BigDecimal.ZERO))
                            .build();
                })
                .toList();
    }

    private CostStats buildCost(DashboardWindow window) {
        CostTotals current = metricsQueries.costTotals(window.fromUtc(), window.toUtc());
        CostTotals previous = metricsQueries.costTotals(window.previousFromUtc(), window.previousToUtc());
        List<PurposeCost> byPurpose = metricsQueries.costByPurpose(window.fromUtc(), window.toUtc()).stream()
                .map(row -> new PurposeCost(UsagePurpose.valueOf(row.purpose()), row.costUsd(), row.requestCount()))
                .toList();

        return CostStats.builder()
                .totalUsd(current.pricedUsd())
                .previousTotalUsd(previous.pricedUsd())
                .unpricedEstimateUsd(current.unpricedUsd())
                .requestCount(current.requestCount())
                .byPurpose(byPurpose)
                .byModel(buildModelCosts(window))
                .budgets(buildTopBudgets())
                .build();
    }

    private List<ModelCost> buildModelCosts(DashboardWindow window) {
        Map<String, BigDecimal> previous = metricsQueries.pricedCostByModel(
                window.previousFromUtc(), window.previousToUtc());
        return metricsQueries.costByModel(window.fromUtc(), window.toUtc()).stream()
                .map(row -> ModelCost.builder()
                        .provider(row.provider())
                        .modelName(row.modelName())
                        .requestCount(row.requestCount())
                        .callCount(row.callCount())
                        .errorCount(row.errorCount())
                        .inputTokens(row.inputTokens())
                        .outputTokens(row.outputTokens())
                        .costUsd(row.pricedUsd())
                        .previousCostUsd(previous.getOrDefault(
                                DashboardMetricsQueries.modelKey(row.provider(), row.modelName()),
                                BigDecimal.ZERO))
                        .unpricedEstimateUsd(row.unpricedEstimateUsd())
                        .medianLatencyMs(row.medianLatencyMs())
                        .build())
                .toList();
    }

    /** Enabled budgets closest to (or past) their limit, measured on each budget's own period. */
    private List<BudgetUsage> buildTopBudgets() {
        return budgetService.getAll().stream()
                .filter(budget -> Boolean.TRUE.equals(budget.isEnabled())
                        && !Boolean.FALSE.equals(budget.isActive())
                        && budget.spentPercent() != null)
                .sorted(Comparator.comparing(BudgetResponse::spentPercent).reversed())
                .limit(TOP_BUDGETS)
                .map(budget -> BudgetUsage.builder()
                        .id(budget.id().toString())
                        .scope(budget.scope())
                        .scopeProvider(budget.scopeProvider())
                        .scopePurpose(budget.scopePurpose())
                        .period(budget.period())
                        .spentUsd(budget.spentUsd())
                        .limitUsd(budget.limitUsd())
                        .spentPercent(budget.spentPercent())
                        .build())
                .toList();
    }

    private DocumentStats buildDocumentStats() {
        long published = documentRepository.countByStatusAndDeletedAtIsNull(DocStatus.COMPLETED);
        long departments = documentRepository.countDistinctDepartmentsByStatus(DocStatus.COMPLETED);
        return DocumentStats.builder().published(published).departments(departments).build();
    }
}
