package com.unisage.backend.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.unisage.backend.service.backup.RestoreJobs;

/** Progress of the latest Postgres restore, read from the restore folder, not the database. */
public record PostgresRestoreStatusResponse(
        UUID restoreRunId,
        RestoreJobs.Phase phase,
        String message,
        Instant updatedAt,
        boolean active) {

    public static PostgresRestoreStatusResponse from(RestoreJobs.JobStatus job) {
        return new PostgresRestoreStatusResponse(job.id(), job.phase(), job.message(), job.updatedAt(),
                job.phase().isActive());
    }
}
