package com.unisage.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.Department;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    @Query("SELECT d FROM Department d WHERE (:isActive IS NULL OR d.isActive = :isActive)")
    List<Department> findAllByIsActive(@Param("isActive") Boolean isActive);

    @Query("SELECT d FROM Department d WHERE d.parent.id = :parentId AND (:isActive IS NULL OR d.isActive = :isActive)")
    List<Department> findByParentIdAndIsActive(@Param("parentId") UUID parentId, @Param("isActive") Boolean isActive);

    @Query("SELECT d FROM Department d WHERE LOWER(d.name) LIKE LOWER(CONCAT('%', :name, '%')) AND (:isActive IS NULL OR d.isActive = :isActive)")
    List<Department> findByNameContainingAndIsActive(@Param("name") String name, @Param("isActive") Boolean isActive);

    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    List<Department> findByParentId(UUID parentId);

    List<Department> findByParentIsNull();

    Optional<Department> findByName(String name);
}
