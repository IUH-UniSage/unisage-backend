package com.unisage.backend.entity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Retrieval trace of one assistant message, pushed by unisage-agent: the trace id of the turn's log
 * lines plus the chunks and web pages given to the LLM. Staff-only: read by the staff ticket detail,
 * never by the public conversation history. Written only through the native upsert in
 * {@code MessageTraceRepository}, so no auditing fields (the agent has no user identity).
 */
@Entity
@Table(name = "message_traces")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageTrace {

    @Id
    @Column(name = "message_id", updatable = false, nullable = false)
    private UUID messageId;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> chunks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "web_sources", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> webSources;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
