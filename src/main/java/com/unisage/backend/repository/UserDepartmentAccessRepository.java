package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.UserDepartmentAccess;

@Repository
public interface UserDepartmentAccessRepository
        extends JpaRepository<UserDepartmentAccess, UserDepartmentAccess.UserDepartmentAccessId> {

    @Query("SELECT COUNT(u) > 0 FROM UserDepartmentAccess u WHERE u.role.id = :roleId AND u.department.id = :departmentId")
    boolean existsByRoleIdAndDepartmentId(@Param("roleId") UUID roleId, @Param("departmentId") UUID departmentId);

    @Query("SELECT u FROM UserDepartmentAccess u WHERE u.role.id = :roleId")
    List<UserDepartmentAccess> findByRoleId(@Param("roleId") UUID roleId);

    @Query("SELECT u FROM UserDepartmentAccess u WHERE u.department.id = :departmentId")
    List<UserDepartmentAccess> findByDepartmentId(@Param("departmentId") UUID departmentId);

    @Modifying
    @Query("DELETE FROM UserDepartmentAccess u WHERE u.role.id = :roleId AND u.department.id = :departmentId")
    int deleteByRoleIdAndDepartmentId(@Param("roleId") UUID roleId, @Param("departmentId") UUID departmentId);
}
