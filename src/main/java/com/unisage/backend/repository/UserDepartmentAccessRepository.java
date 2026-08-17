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

    @Query("SELECT COUNT(u) > 0 FROM UserDepartmentAccess u WHERE u.user.id = :userId AND u.department.id = :departmentId")
    boolean existsByUserIdAndDepartmentId(@Param("userId") UUID userId, @Param("departmentId") UUID departmentId);

    @Query("SELECT u FROM UserDepartmentAccess u WHERE u.user.id = :userId")
    List<UserDepartmentAccess> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT u FROM UserDepartmentAccess u WHERE u.department.id = :departmentId")
    List<UserDepartmentAccess> findByDepartmentId(@Param("departmentId") UUID departmentId);

    @Modifying
    @Query("DELETE FROM UserDepartmentAccess u WHERE u.user.id = :userId AND u.department.id = :departmentId")
    int deleteByUserIdAndDepartmentId(@Param("userId") UUID userId, @Param("departmentId") UUID departmentId);

    @Modifying
    @Query("DELETE FROM UserDepartmentAccess u WHERE u.user.id = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
