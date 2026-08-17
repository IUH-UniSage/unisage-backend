package com.unisage.backend.service.rbac;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.request.PermissionRequest;
import com.unisage.backend.dto.request.RoleRequest;
import com.unisage.backend.dto.response.RoleResponse;
import com.unisage.backend.dto.response.PermissionResponse;

public interface RbacService {
    // --- ROLES ---
    RoleResponse createRole(RoleRequest request);
    RoleResponse updateRole(UUID roleId, RoleRequest request);
    void deleteRole(UUID roleId);
    void recoverRole(UUID roleId);
    void deleteResources(List<UUID> roleIds);
    void recoverResources(List<UUID> roleIds);
    RoleResponse getRoleDetails(UUID roleId);
    PageResponse<List<RoleResponse>> getAllRoles(Pageable pageable);

    // --- PERMISSIONS ---
    PermissionResponse createPermission(PermissionRequest request);
    PermissionResponse updatePermission(UUID permissionId, PermissionRequest request);
    void deletePermission(UUID permissionId);
    void recoverPermission(UUID permissionId);
    void deletePermissionResources(List<UUID> permissionIds);
    void recoverPermissionResources(List<UUID> permissionIds);
    PageResponse<List<PermissionResponse>> getAllPermissions(Pageable pageable);

    // --- ASSIGNMENTS ---
    void assignPermissionToRole(UUID roleId, UUID permissionId);
}
