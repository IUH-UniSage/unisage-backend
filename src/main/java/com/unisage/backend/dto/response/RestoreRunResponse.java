package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.RestoreRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.RestoreType;

public record RestoreRunResponse(
        UUID id,
        RestoreType restoreType,
        BackupStatus status,
        UUID sourceRunId,
        String target,
        LocalDateTime targetTime,
        UUID safetyRunId,
        String requestedByCode,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMessage) {

    public static RestoreRunResponse from(RestoreRun run) {
        return new RestoreRunResponse(run.getId(), run.getRestoreType(), run.getStatus(), run.getSourceRunId(),
                run.getTarget(), run.getTargetTime(), run.getSafetyRunId(), run.getRequestedByCode(),
                run.getStartedAt(), run.getFinishedAt(), run.getErrorMessage());
    }
}
