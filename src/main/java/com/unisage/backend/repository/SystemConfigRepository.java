package com.unisage.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.SystemConfig;
import com.unisage.backend.entity.enums.SystemConfigCategory;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, UUID> {

    Optional<SystemConfig> findByConfigKey(String configKey);

    @Override
    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    List<SystemConfig> findAll();

    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    List<SystemConfig> findAllByCategory(SystemConfigCategory category);
}
