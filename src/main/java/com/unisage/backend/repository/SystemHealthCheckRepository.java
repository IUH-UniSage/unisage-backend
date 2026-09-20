package com.unisage.backend.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.SystemHealthCheck;

@Repository
public interface SystemHealthCheckRepository
        extends JpaRepository<SystemHealthCheck, UUID>, JpaSpecificationExecutor<SystemHealthCheck> {
}
