package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.RequestUsageLine;

public interface RequestUsageLineRepository extends JpaRepository<RequestUsageLine, UUID> {

    List<RequestUsageLine> findByUsageLogIdOrderBySeq(UUID usageLogId);

    /** One batch query for the list endpoint's {@code hasFailover} flag - avoids N+1 per row. */
    @Query("SELECT DISTINCT l.usageLog.id FROM RequestUsageLine l "
            + "WHERE l.usageLog.id IN :usageLogIds AND l.attempt >= 1")
    List<UUID> findUsageLogIdsWithFailover(@Param("usageLogIds") List<UUID> usageLogIds);

    /** Distinct (usageLogId, modelName) pairs for the list endpoint's model column - one query per page. */
    @Query("SELECT DISTINCT l.usageLog.id, l.modelName FROM RequestUsageLine l "
            + "WHERE l.usageLog.id IN :usageLogIds AND l.modelName IS NOT NULL")
    List<Object[]> findModelNamesByUsageLogIds(@Param("usageLogIds") List<UUID> usageLogIds);
}
