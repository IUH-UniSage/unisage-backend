package com.unisage.backend.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.unisage.backend.entity.RequestUsageLog;

public interface RequestUsageLogRepository
        extends JpaRepository<RequestUsageLog, UUID>, JpaSpecificationExecutor<RequestUsageLog> {

    Optional<RequestUsageLog> findByRequestId(UUID requestId);
}
