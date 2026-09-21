package com.unisage.backend.service.rbac;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.request.RoleRequest;
import com.unisage.backend.dto.response.RoleResponse;
import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.UsageLimitPlan;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.PermissionRepository;
import com.unisage.backend.repository.RolePermissionRepository;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.repository.UsageLimitPlanRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers only how a role is tied to a usage limit plan. */
class RbacServiceImplUsageLimitPlanTest {

    private RoleRepository roleRepository;
    private UsageLimitPlanRepository planRepository;
    private RbacServiceImpl service;

    private final UsageLimitPlan plan = UsageLimitPlan.builder().id(UUID.randomUUID()).name("Gói nhỏ").build();

    @BeforeEach
    void setUp() {
        roleRepository = mock(RoleRepository.class);
        planRepository = mock(UsageLimitPlanRepository.class);
        RolePermissionRepository rolePermissionRepository = mock(RolePermissionRepository.class);
        service = new RbacServiceImpl(roleRepository, mock(PermissionRepository.class), rolePermissionRepository,
                planRepository);

        when(roleRepository.save(any(Role.class))).thenAnswer(i -> {
            Role role = i.getArgument(0);
            if (role.getId() == null) {
                role.setId(UUID.randomUUID());
            }
            when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));
            return role;
        });
        when(rolePermissionRepository.findByRoleId(any())).thenReturn(List.of());
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    }

    @Test
    void createRole_withPlan_linksItAndReturnsItInTheResponse() {
        RoleResponse response = service.createRole(request("Giảng viên", plan.getId()));

        assertThat(response.usageLimitPlan()).isNotNull();
        assertThat(response.usageLimitPlan().id()).isEqualTo(plan.getId());
        assertThat(response.usageLimitPlan().name()).isEqualTo("Gói nhỏ");
    }

    @Test
    void createRole_withoutPlan_leavesItNullSoTheDefaultApplies() {
        RoleResponse response = service.createRole(request("Khách mời", null));

        assertThat(response.usageLimitPlan()).isNull();
    }

    @Test
    void createRole_unknownPlan_throwsPlanNotFound() {
        UUID unknown = UUID.randomUUID();
        when(planRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRole(request("Khách mời", unknown)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_NOT_FOUND));
    }

    @Test
    void updateRole_changesThePlan() {
        Role role = Role.builder().id(UUID.randomUUID()).name("Giảng viên").isSystemRole(false).build();
        when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));

        RoleResponse response = service.updateRole(role.getId(), request("Giảng viên", plan.getId()));

        assertThat(role.getUsageLimitPlan()).isSameAs(plan);
        assertThat(response.usageLimitPlan().id()).isEqualTo(plan.getId());
    }

    @Test
    void updateRole_withNullPlan_backToDefault() {
        Role role = Role.builder().id(UUID.randomUUID()).name("Giảng viên").isSystemRole(false)
                .usageLimitPlan(plan).build();
        when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));

        RoleResponse response = service.updateRole(role.getId(), request("Giảng viên", null));

        assertThat(role.getUsageLimitPlan()).isNull();
        assertThat(response.usageLimitPlan()).isNull();
    }

    private static RoleRequest request(String name, UUID planId) {
        return RoleRequest.builder().name(name).isSystemRole(false).usageLimitPlanId(planId).build();
    }
}
