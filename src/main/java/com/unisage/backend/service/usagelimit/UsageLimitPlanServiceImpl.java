package com.unisage.backend.service.usagelimit;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.UsageLimitPlanRequest;
import com.unisage.backend.dto.response.UsageLimitPlanResponse;
import com.unisage.backend.entity.UsageLimitPlan;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.repository.UsageLimitPlanRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

/**
 * Invariant kept here and in the database: exactly one plan is the default. It can be moved to
 * another plan but never removed, so guests and role-less users always resolve to a plan.
 */
@Service
@RequiredArgsConstructor
public class UsageLimitPlanServiceImpl implements UsageLimitPlanService {

    private final UsageLimitPlanRepository usageLimitPlanRepository;
    private final RoleRepository roleRepository;

    @Override
    @Transactional
    public UsageLimitPlanResponse createPlan(UsageLimitPlanRequest request) {
        if (usageLimitPlanRepository.existsByName(request.name())) {
            throw new AppException(ErrorCode.USAGE_LIMIT_PLAN_NAME_EXISTED);
        }

        boolean makeDefault = Boolean.TRUE.equals(request.isDefault());
        if (makeDefault) {
            usageLimitPlanRepository.clearDefault();
        }

        UsageLimitPlan plan = UsageLimitPlan.builder()
                .name(request.name())
                .dailyTokenLimit(request.dailyTokenLimit())
                .weeklyTokenLimit(request.weeklyTokenLimit())
                .isDefault(makeDefault)
                .build();
        return mapToResponse(usageLimitPlanRepository.save(plan));
    }

    @Override
    @Transactional
    public UsageLimitPlanResponse updatePlan(UUID id, UsageLimitPlanRequest request) {
        UsageLimitPlan plan = usageLimitPlanRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USAGE_LIMIT_PLAN_NOT_FOUND));

        if (usageLimitPlanRepository.existsByNameAndIdNot(request.name(), id)) {
            throw new AppException(ErrorCode.USAGE_LIMIT_PLAN_NAME_EXISTED);
        }
        if (Boolean.FALSE.equals(request.isDefault()) && Boolean.TRUE.equals(plan.getIsDefault())) {
            throw new AppException(ErrorCode.USAGE_LIMIT_PLAN_DEFAULT_PROTECTED);
        }

        if (Boolean.TRUE.equals(request.isDefault()) && !Boolean.TRUE.equals(plan.getIsDefault())) {
            usageLimitPlanRepository.clearDefault();
            plan.setIsDefault(true);
        }
        plan.setName(request.name());
        plan.setDailyTokenLimit(request.dailyTokenLimit());
        plan.setWeeklyTokenLimit(request.weeklyTokenLimit());
        return mapToResponse(usageLimitPlanRepository.save(plan));
    }

    @Override
    public UsageLimitPlanResponse getPlanById(UUID id) {
        return mapToResponse(usageLimitPlanRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USAGE_LIMIT_PLAN_NOT_FOUND)));
    }

    @Override
    public List<UsageLimitPlanResponse> getAllPlans() {
        return usageLimitPlanRepository.findAllByOrderByNameAsc().stream().map(this::mapToResponse).toList();
    }

    /**
     * Hard delete: a plan can only be removed while no role points at it, so nothing is left
     * dangling, and keeping soft-deleted rows would only reserve their unique name. The removal is
     * still recorded by the audit trail.
     */
    @Override
    @Transactional
    public void deletePlan(UUID id) {
        UsageLimitPlan plan = usageLimitPlanRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USAGE_LIMIT_PLAN_NOT_FOUND));

        if (Boolean.TRUE.equals(plan.getIsDefault())) {
            throw new AppException(ErrorCode.USAGE_LIMIT_PLAN_DEFAULT_PROTECTED);
        }
        if (roleRepository.existsByUsageLimitPlanId(id)) {
            throw new AppException(ErrorCode.USAGE_LIMIT_PLAN_IN_USE);
        }
        usageLimitPlanRepository.delete(plan);
    }

    private UsageLimitPlanResponse mapToResponse(UsageLimitPlan plan) {
        return UsageLimitPlanResponse.builder()
                .id(plan.getId())
                .name(plan.getName())
                .dailyTokenLimit(plan.getDailyTokenLimit())
                .weeklyTokenLimit(plan.getWeeklyTokenLimit())
                .isDefault(plan.getIsDefault())
                .isActive(plan.getIsActive())
                .createdAt(plan.getCreatedAt())
                .createdBy(plan.getCreatedBy() != null ? plan.getCreatedBy().getId().toString() : null)
                .createdByName(plan.getCreatedBy() != null ? plan.getCreatedBy().getFullName() : null)
                .updatedAt(plan.getUpdatedAt())
                .updatedBy(plan.getUpdatedBy() != null ? plan.getUpdatedBy().getId().toString() : null)
                .updatedByName(plan.getUpdatedBy() != null ? plan.getUpdatedBy().getFullName() : null)
                .build();
    }
}
