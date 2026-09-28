package com.unisage.backend.service.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.BudgetRequest;
import com.unisage.backend.dto.response.BudgetResponse;
import com.unisage.backend.dto.response.internal.InternalBudgetSnapshotResponse;
import com.unisage.backend.entity.Budget;
import com.unisage.backend.entity.enums.BudgetAction;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BudgetRepository;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;
import com.unisage.backend.service.usagelog.UsagePeriodCalculator;
import com.unisage.backend.service.usagelog.UsagePeriodCalculator.PeriodBounds;
import com.unisage.backend.service.usagelog.UsagePeriodTotalsService;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BudgetServiceImpl implements BudgetService {

    private final BudgetRepository budgetRepository;
    private final UsagePeriodCalculator periodCalculator;
    private final UsagePeriodTotalsService periodTotalsService;
    private final ModelRegistryVersionService modelRegistryVersionService;

    @Override
    @Transactional
    public BudgetResponse create(BudgetRequest request) {
        validate(request);

        Budget budget = Budget.builder()
                .scope(request.scope())
                .scopeProvider(request.scope() == BudgetScope.PROVIDER ? request.scopeProvider() : null)
                .scopePurpose(request.scope() == BudgetScope.PURPOSE ? request.scopePurpose() : null)
                .period(request.period())
                .limitUsd(request.limitUsd())
                .action(request.action())
                .throttleMaxConcurrency(request.action() == BudgetAction.THROTTLE ? request.throttleMaxConcurrency() : null)
                .isEnabled(request.isEnabled() == null || request.isEnabled())
                .build();

        budget = saveOrConflict(budget);
        modelRegistryVersionService.bump();
        return mapToResponse(budget);
    }

    @Override
    @Transactional
    public BudgetResponse update(UUID id, BudgetRequest request) {
        Budget budget = budgetRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.BUDGET_NOT_FOUND));
        validate(request);

        budget.setScope(request.scope());
        budget.setScopeProvider(request.scope() == BudgetScope.PROVIDER ? request.scopeProvider() : null);
        budget.setScopePurpose(request.scope() == BudgetScope.PURPOSE ? request.scopePurpose() : null);
        budget.setPeriod(request.period());
        budget.setLimitUsd(request.limitUsd());
        budget.setAction(request.action());
        budget.setThrottleMaxConcurrency(
                request.action() == BudgetAction.THROTTLE ? request.throttleMaxConcurrency() : null);
        if (request.isEnabled() != null) {
            budget.setIsEnabled(request.isEnabled());
        }

        budget = saveOrConflict(budget);
        modelRegistryVersionService.bump();
        return mapToResponse(budget);
    }

    @Override
    public BudgetResponse getById(UUID id) {
        Budget budget = budgetRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.BUDGET_NOT_FOUND));
        return mapToResponse(budget);
    }

    @Override
    public List<BudgetResponse> getAll() {
        return budgetRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Budget budget = budgetRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.BUDGET_NOT_FOUND));
        // Also clear isEnabled so the row immediately frees its slot in the partial unique index -
        // a soft-deleted (isActive=false) but still-enabled row would otherwise keep blocking a new
        // budget for the same scope/period until someone notices and disables it by hand.
        budget.setIsActive(false);
        budget.setIsEnabled(false);
        budgetRepository.save(budget);
        modelRegistryVersionService.bump();
    }

    @Override
    public InternalBudgetSnapshotResponse getSnapshot() {
        List<InternalBudgetSnapshotResponse.BudgetEntry> entries = budgetRepository.findByIsEnabledTrueAndIsActiveTrue()
                .stream()
                .map(b -> InternalBudgetSnapshotResponse.BudgetEntry.builder()
                        .scope(b.getScope())
                        .scopeProvider(b.getScopeProvider())
                        .scopePurpose(b.getScopePurpose())
                        .period(b.getPeriod())
                        .limitUsd(b.getLimitUsd())
                        .action(b.getAction())
                        .throttleMaxConcurrency(b.getThrottleMaxConcurrency())
                        .build())
                .toList();

        return InternalBudgetSnapshotResponse.builder()
                .version(modelRegistryVersionService.currentVersion())
                .budgets(entries)
                .build();
    }

    private Budget saveOrConflict(Budget budget) {
        try {
            return budgetRepository.saveAndFlush(budget);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.BUDGET_ALREADY_ENABLED_FOR_PERIOD);
        }
    }

    private void validate(BudgetRequest request) {
        boolean scopeConsistent = switch (request.scope()) {
            case SYSTEM -> request.scopeProvider() == null && request.scopePurpose() == null;
            case PROVIDER -> request.scopeProvider() != null && request.scopePurpose() == null;
            case PURPOSE -> request.scopeProvider() == null && request.scopePurpose() != null;
        };
        if (!scopeConsistent) {
            throw new AppException(ErrorCode.BUDGET_INVALID_SCOPE);
        }

        boolean throttleConsistent = request.action() == BudgetAction.THROTTLE
                ? request.throttleMaxConcurrency() != null && request.throttleMaxConcurrency() > 0
                : request.throttleMaxConcurrency() == null;
        if (!throttleConsistent) {
            throw new AppException(ErrorCode.BUDGET_INVALID_THROTTLE);
        }
    }

    private BudgetResponse mapToResponse(Budget budget) {
        BigDecimal spentUsd = null;
        Double spentPercent = null;
        if (Boolean.TRUE.equals(budget.getIsEnabled())) {
            PeriodBounds bounds = periodCalculator.currentPeriodBoundsUtc(budget.getPeriod());
            spentUsd = periodTotalsService.spentUsd(budget.getScope(), budget.getScopeProvider(),
                    budget.getScopePurpose(), bounds.startUtc(), bounds.endUtc());
            if (budget.getLimitUsd() != null && budget.getLimitUsd().signum() > 0) {
                spentPercent = spentUsd
                        .divide(budget.getLimitUsd(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
            }
        }

        return BudgetResponse.builder()
                .id(budget.getId())
                .scope(budget.getScope())
                .scopeProvider(budget.getScopeProvider())
                .scopePurpose(budget.getScopePurpose())
                .period(budget.getPeriod())
                .limitUsd(budget.getLimitUsd())
                .action(budget.getAction())
                .throttleMaxConcurrency(budget.getThrottleMaxConcurrency())
                .isEnabled(budget.getIsEnabled())
                .spentUsd(spentUsd)
                .spentPercent(spentPercent)
                .isActive(budget.getIsActive())
                .createdAt(budget.getCreatedAt())
                .createdBy(budget.getCreatedBy() != null ? budget.getCreatedBy().getId().toString() : null)
                .updatedAt(budget.getUpdatedAt())
                .updatedBy(budget.getUpdatedBy() != null ? budget.getUpdatedBy().getId().toString() : null)
                .build();
    }
}
