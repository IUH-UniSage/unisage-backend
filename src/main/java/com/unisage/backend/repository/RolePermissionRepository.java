package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;
import com.unisage.backend.entity.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.RolePermissionId> {
    List<RolePermission> findByRoleId(UUID roleId);
    void deleteByRoleId(UUID roleId);
    void deleteByPermissionId(UUID permissionId);
    void deleteByRoleIdAndPermissionId(UUID roleId, UUID permissionId);
    boolean existsByRoleAndPermission(Role role, Permission permission);
}
