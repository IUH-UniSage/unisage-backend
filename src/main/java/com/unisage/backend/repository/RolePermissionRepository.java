package com.unisage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import com.unisage.backend.entity.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.RolePermissionId> {
    List<RolePermission> findByRoleId(UUID roleId);

    /** Every mapping of a page of roles in one query, permission included (role list, no N+1). */
    @Query("SELECT rp FROM RolePermission rp JOIN FETCH rp.permission WHERE rp.role.id IN :roleIds")
    List<RolePermission> findWithPermissionByRoleIdIn(@Param("roleIds") Collection<UUID> roleIds);
    void deleteByRoleId(UUID roleId);
    void deleteByPermissionId(UUID permissionId);
    void deleteByRoleIdAndPermissionId(UUID roleId, UUID permissionId);
    boolean existsByRoleAndPermission(Role role, Permission permission);
}
