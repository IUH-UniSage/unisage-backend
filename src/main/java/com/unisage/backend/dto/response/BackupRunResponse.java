package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;

/** One backup_runs row; {@code scheduledFor} is app.timezone wall time, the rest UTC. */
public record BackupRunResponse(
        UUID id,
        BackupType backupType,
        BackupStatus status,
        String target,
        LocalDateTime scheduledFor,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        UUID baseRunId,
        String storagePrefix,
        Long sizeBytes,
        Integer fileCount,
        String errorMessage,
        LocalDateTime purgedAt) {

    public static BackupRunResponse from(BackupRun run) {
        return new BackupRunResponse(run.getId(), run.getBackupType(), run.getStatus(), run.getTarget(),
                run.getScheduledFor(), run.getStartedAt(), run.getFinishedAt(), run.getBaseRunId(),
                run.getStoragePrefix(), run.getSizeBytes(), run.getFileCount(), run.getErrorMessage(),
                run.getPurgedAt());
    }
}
