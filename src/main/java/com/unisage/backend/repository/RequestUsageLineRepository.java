package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.unisage.backend.entity.RequestUsageLine;

public interface RequestUsageLineRepository extends JpaRepository<RequestUsageLine, UUID> {

    List<RequestUsageLine> findByUsageLogIdOrderBySeq(UUID usageLogId);
}
