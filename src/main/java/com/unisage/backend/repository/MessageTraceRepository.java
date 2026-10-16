package com.unisage.backend.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.MessageTrace;

public interface MessageTraceRepository extends JpaRepository<MessageTrace, UUID> {

    /**
     * Idempotent write keyed by {@code message_id}: a retried push replaces the trace instead of
     * failing on the primary key, and two concurrent pushes cannot both insert.
     */
    @Modifying
    @Query(value = """
        INSERT INTO message_traces (message_id, trace_id, chunks, web_sources, created_at)
        VALUES (:messageId, :traceId, CAST(:chunks AS jsonb), CAST(:webSources AS jsonb), now())
        ON CONFLICT (message_id)
        DO UPDATE SET trace_id = EXCLUDED.trace_id, chunks = EXCLUDED.chunks,
                      web_sources = EXCLUDED.web_sources, created_at = EXCLUDED.created_at
        """, nativeQuery = true)
    int upsert(@Param("messageId") UUID messageId, @Param("traceId") String traceId,
               @Param("chunks") String chunksJson, @Param("webSources") String webSourcesJson);
}
