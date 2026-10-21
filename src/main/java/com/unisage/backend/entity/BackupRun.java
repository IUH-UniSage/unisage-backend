package com.unisage.backend.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;

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
 * One scheduled backup run (UNISAGE-102). Not a {@link BaseEntity}: rows are written by the
 * scheduler, never by a user, and must not themselves be audited (same as {@link SystemHealthCheck}).
 *
 * <p>{@code scheduledFor} is the schedule slot in {@code app.timezone} wall time, the clock an
 * admin sets the backup hour in; the other timestamps are UTC.
 */
@Entity
@Table(name = "backup_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupRun {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "backup_type", updatable = false, nullable = false, length = 32)
    private BackupType backupType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackupStatus status;

    @Column(updatable = false)
    private String target;

    @Column(name = "scheduled_for", updatable = false, nullable = false)
    private LocalDateTime scheduledFor;

    @Column(name = "started_at", updatable = false, nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "base_run_id", updatable = false)
    private UUID baseRunId;

    @Column(name = "storage_prefix", length = 512)
    private String storagePrefix;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "file_count")
    private Integer fileCount;

    @Column(length = 128)
    private String checksum;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "purged_at")
    private LocalDateTime purgedAt;
}
