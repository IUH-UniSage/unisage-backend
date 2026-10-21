package com.unisage.backend.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.RestoreType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One restore an admin started from the backup page (UNISAGE-102, ADR-0010). Not a
 * {@link BaseEntity}, for the same reason as {@link BackupRun}. Timestamps are UTC.
 */
@Entity
@Table(name = "restore_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestoreRun {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "restore_type", updatable = false, nullable = false, length = 32)
    private RestoreType restoreType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackupStatus status;

    @Column(name = "source_run_id")
    private UUID sourceRunId;

    @Column(updatable = false)
    private String target;

    @Column(name = "target_time", updatable = false)
    private LocalDateTime targetTime;

    @Column(name = "safety_run_id")
    private UUID safetyRunId;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_by_code", updatable = false, length = 64)
    private String requestedByCode;

    @Column(name = "started_at", updatable = false, nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
