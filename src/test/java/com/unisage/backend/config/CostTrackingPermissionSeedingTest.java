package com.unisage.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.unisage.backend.entity.Permission;
import com.unisage.backend.entity.Role;
import com.unisage.backend.predefined.PredefinedPermissions;
import com.unisage.backend.predefined.PredefinedRoles;
import com.unisage.backend.repository.PermissionRepository;
import com.unisage.backend.repository.RolePermissionRepository;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Cost Tracking plan.md Task 3: a new resource is unreachable unless it's wired into the
 * permission system - DataInitializer must seed USAGE_LOG_READ/BUDGET_* and grant them to
 * SUPER_ADMIN on a fresh DB (V27 covers the "DB already exists" case separately). */
class CostTrackingPermissionSeedingTest extends PostgresIntegrationTest {

    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Test
    void superAdminHasAllCostTrackingPermissions() {
        Role superAdmin = roleRepository.findByName(PredefinedRoles.SUPER_ADMIN).orElseThrow();

        for (String permissionName : new String[] {
                PredefinedPermissions.USAGE_LOG_READ,
                PredefinedPermissions.BUDGET_ALL,
                PredefinedPermissions.BUDGET_ALERT_SETTING_READ,
                PredefinedPermissions.BUDGET_ALERT_SETTING_UPDATE,
                PredefinedPermissions.BUDGET_ALERT_READ,
                PredefinedPermissions.BUDGET_ALERT_DISMISS,
        }) {
            Permission permission = permissionRepository.findByName(permissionName)
                    .orElseThrow(() -> new AssertionError("permission not seeded: " + permissionName));
            assertThat(rolePermissionRepository.existsByRoleAndPermission(superAdmin, permission))
                    .as(permissionName + " granted to SUPER_ADMIN")
                    .isTrue();
        }
    }
}
