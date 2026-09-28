package com.unisage.backend.service.budget;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.unisage.backend.dto.request.BudgetRequest;
import com.unisage.backend.entity.enums.BudgetAction;
import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BudgetRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 3 verification: plan.md "budget SYSTEM MONTHLY thứ 2 enabled → lỗi validate; tạo đồng
 * thời 2 cái → đúng 1 thành công". */
class BudgetServiceImplTest extends PostgresIntegrationTest {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetRepository budgetRepository;

    @AfterEach
    void cleanUp() {
        // Concurrency test spawns worker threads whose commits are real (outside any test
        // transaction), so this must be an explicit, unconditional delete rather than relying on
        // Spring test rollback - otherwise a row created by one test leaks into the next and
        // trips the same unique index the next test is trying to exercise from a clean slate.
        budgetRepository.deleteAll();
    }

    private BudgetRequest systemMonthly(BigDecimal limit) {
        return BudgetRequest.builder()
                .scope(BudgetScope.SYSTEM)
                .period(BudgetPeriod.MONTHLY)
                .limitUsd(limit)
                .action(BudgetAction.ALERT)
                .build();
    }

    @Test
    void secondEnabledSystemMonthlyBudget_isRejected() {
        budgetService.create(systemMonthly(BigDecimal.valueOf(100)));

        assertThatThrownBy(() -> budgetService.create(systemMonthly(BigDecimal.valueOf(200))))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUDGET_ALREADY_ENABLED_FOR_PERIOD);
    }

    @Test
    void concurrentCreate_onlyOneSucceeds() throws InterruptedException {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    budgetService.create(systemMonthly(BigDecimal.TEN));
                    successCount.incrementAndGet();
                } catch (AppException e) {
                    if (e.getErrorCode() == ErrorCode.BUDGET_ALREADY_ENABLED_FOR_PERIOD) {
                        conflictCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threads - 1);
    }

    @Test
    void invalidScopeReference_isRejectedBeforeHittingDb() {
        BudgetRequest invalid = BudgetRequest.builder()
                .scope(BudgetScope.PROVIDER)
                .scopeProvider(null)
                .period(BudgetPeriod.DAILY)
                .limitUsd(BigDecimal.TEN)
                .action(BudgetAction.ALERT)
                .build();

        assertThatThrownBy(() -> budgetService.create(invalid))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUDGET_INVALID_SCOPE);
    }

    @Test
    void throttleActionWithoutConcurrency_isRejected() {
        BudgetRequest invalid = BudgetRequest.builder()
                .scope(BudgetScope.SYSTEM)
                .period(BudgetPeriod.DAILY)
                .limitUsd(BigDecimal.TEN)
                .action(BudgetAction.THROTTLE)
                .throttleMaxConcurrency(null)
                .build();

        assertThatThrownBy(() -> budgetService.create(invalid))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUDGET_INVALID_THROTTLE);
    }

    @Test
    void deletingBudget_freesItsSlotForANewOne() {
        var created = budgetService.create(systemMonthly(BigDecimal.TEN));
        budgetService.delete(created.id());

        var recreated = budgetService.create(systemMonthly(BigDecimal.valueOf(50)));
        assertThat(recreated.id()).isNotEqualTo(created.id());
    }

    @Test
    void purposeBudget_roundTrips() {
        BudgetRequest request = BudgetRequest.builder()
                .scope(BudgetScope.PURPOSE)
                .scopePurpose(UsagePurpose.EMBEDDING)
                .period(BudgetPeriod.DAILY)
                .limitUsd(BigDecimal.ONE)
                .action(BudgetAction.BLOCK)
                .build();

        var response = budgetService.create(request);
        assertThat(response.scopePurpose()).isEqualTo(UsagePurpose.EMBEDDING);
        assertThat(response.spentUsd()).isNotNull();

        List<UUID> ids = budgetService.getAll().stream().map(r -> r.id()).toList();
        assertThat(ids).contains(response.id());
    }
}
