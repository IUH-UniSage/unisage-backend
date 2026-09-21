package com.unisage.backend.service.usagelimit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.unisage.backend.dto.request.UsageLimitPlanRequest;
import com.unisage.backend.dto.response.UsageLimitPlanResponse;
import com.unisage.backend.entity.UsageLimitPlan;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.repository.UsageLimitPlanRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageLimitPlanServiceImplTest {

    private UsageLimitPlanRepository planRepository;
    private RoleRepository roleRepository;
    private UsageLimitPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        planRepository = mock(UsageLimitPlanRepository.class);
        roleRepository = mock(RoleRepository.class);
        service = new UsageLimitPlanServiceImpl(planRepository, roleRepository);
        when(planRepository.save(any(UsageLimitPlan.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ── create ───────────────────────────────────────────────────────────

    @Test
    void create_duplicateName_throwsNameExisted() {
        when(planRepository.existsByName("Gói A")).thenReturn(true);

        assertThatThrownBy(() -> service.createPlan(request("Gói A", 10L, 100L, false)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_NAME_EXISTED));
        verify(planRepository, never()).save(any());
    }

    @Test
    void create_regularPlan_isNotDefaultAndLeavesCurrentDefaultAlone() {
        UsageLimitPlanResponse response = service.createPlan(request("Gói A", 10L, 100L, null));

        assertThat(response.isDefault()).isFalse();
        assertThat(response.dailyTokenLimit()).isEqualTo(10L);
        verify(planRepository, never()).clearDefault();
    }

    @Test
    void create_asDefault_clearsPreviousDefaultBeforeSaving() {
        UsageLimitPlanResponse response = service.createPlan(request("Mới", 10L, 100L, true));

        assertThat(response.isDefault()).isTrue();
        InOrder order = inOrder(planRepository);
        order.verify(planRepository).clearDefault();
        order.verify(planRepository).save(any(UsageLimitPlan.class));
    }

    @Test
    void create_nullLimits_meanUnlimited() {
        UsageLimitPlanResponse response = service.createPlan(request("Vô hạn", null, null, false));

        assertThat(response.dailyTokenLimit()).isNull();
        assertThat(response.weeklyTokenLimit()).isNull();
    }

    // ── update ───────────────────────────────────────────────────────────

    @Test
    void update_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(planRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePlan(id, request("X", 1L, 1L, false)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_NOT_FOUND));
    }

    @Test
    void update_nameTakenByAnotherPlan_throwsNameExisted() {
        UsageLimitPlan plan = plan("A", false);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(planRepository.existsByNameAndIdNot("B", plan.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.updatePlan(plan.getId(), request("B", 1L, 1L, false)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_NAME_EXISTED));
    }

    @Test
    void update_changesNameAndLimits() {
        UsageLimitPlan plan = plan("A", false);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        UsageLimitPlanResponse response = service.updatePlan(plan.getId(), request("B", 5L, 50L, null));

        assertThat(response.name()).isEqualTo("B");
        assertThat(response.dailyTokenLimit()).isEqualTo(5L);
        assertThat(response.weeklyTokenLimit()).isEqualTo(50L);
    }

    @Test
    void update_makeDefault_movesTheFlagFromThePreviousDefault() {
        UsageLimitPlan plan = plan("A", false);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        UsageLimitPlanResponse response = service.updatePlan(plan.getId(), request("A", 5L, 50L, true));

        assertThat(response.isDefault()).isTrue();
        InOrder order = inOrder(planRepository);
        order.verify(planRepository).clearDefault();
        order.verify(planRepository).save(plan);
    }

    @Test
    void update_alreadyDefaultKeptDefault_doesNotTouchTheFlag() {
        UsageLimitPlan plan = plan("A", true);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        service.updatePlan(plan.getId(), request("A", 5L, 50L, true));

        verify(planRepository, never()).clearDefault();
    }

    @Test
    void update_unsetTheDefaultPlan_isRejected() {
        UsageLimitPlan plan = plan("A", true);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.updatePlan(plan.getId(), request("A", 5L, 50L, false)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_DEFAULT_PROTECTED));
        assertThat(plan.getIsDefault()).isTrue();
    }

    // ── delete ───────────────────────────────────────────────────────────

    @Test
    void delete_defaultPlan_isRejected() {
        UsageLimitPlan plan = plan("Mặc định", true);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.deletePlan(plan.getId()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_DEFAULT_PROTECTED));
        verify(planRepository, never()).delete(any());
    }

    @Test
    void delete_planUsedByARole_isRejected() {
        UsageLimitPlan plan = plan("A", false);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(roleRepository.existsByUsageLimitPlanId(plan.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deletePlan(plan.getId()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_IN_USE));
        verify(planRepository, never()).delete(any());
    }

    @Test
    void delete_unusedRegularPlan_removesIt() {
        UsageLimitPlan plan = plan("A", false);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        service.deletePlan(plan.getId());

        ArgumentCaptor<UsageLimitPlan> captor = ArgumentCaptor.forClass(UsageLimitPlan.class);
        verify(planRepository).delete(captor.capture());
        assertThat(captor.getValue()).isSameAs(plan);
    }

    @Test
    void delete_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(planRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deletePlan(id))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_NOT_FOUND));
    }

    // ── read ─────────────────────────────────────────────────────────────

    @Test
    void getAll_returnsPlansInRepositoryOrder() {
        when(planRepository.findAllByOrderByNameAsc()).thenReturn(List.of(plan("A", true), plan("B", false)));

        assertThat(service.getAllPlans()).extracting(UsageLimitPlanResponse::name).containsExactly("A", "B");
    }

    @Test
    void getById_unknown_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(planRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPlanById(id)).isInstanceOf(AppException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static UsageLimitPlanRequest request(String name, Long daily, Long weekly, Boolean isDefault) {
        return UsageLimitPlanRequest.builder()
                .name(name).dailyTokenLimit(daily).weeklyTokenLimit(weekly).isDefault(isDefault).build();
    }

    private static UsageLimitPlan plan(String name, boolean isDefault) {
        return UsageLimitPlan.builder().id(UUID.randomUUID()).name(name)
                .dailyTokenLimit(1L).weeklyTokenLimit(1L).isDefault(isDefault).build();
    }
}
