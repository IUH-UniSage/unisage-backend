package com.unisage.backend.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.CalculationTrace;

public interface CalculationTraceRepository extends JpaRepository<CalculationTrace, UUID> {

    Optional<CalculationTrace> findByMessageIdAndItemId(UUID messageId, String itemId);

    /**
     * Idempotent write keyed by {@code (message_id, item_id)}: a retried push replaces the trace
     * instead of failing on the unique constraint, and two concurrent pushes cannot both insert.
     */
    @Modifying
    @Query(value = """
        INSERT INTO calculation_traces (id, message_id, item_id, run_id, trace, created_at)
        VALUES (:id, :messageId, :itemId, :runId, CAST(:trace AS jsonb), now())
        ON CONFLICT (message_id, item_id)
        DO UPDATE SET run_id = EXCLUDED.run_id, trace = EXCLUDED.trace, created_at = EXCLUDED.created_at
        """, nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("messageId") UUID messageId, @Param("itemId") String itemId,
               @Param("runId") String runId, @Param("trace") String traceJson);
}
