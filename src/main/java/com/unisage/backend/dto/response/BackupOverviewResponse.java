package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the "Sao lưu & khôi phục" page needs at a glance (UNISAGE-102).
 *
 * @param pointInTimeFrom earliest moment a point-in-time restore can target (UTC), null if none
 * @param pointInTimeTo   latest such moment: now when WAL archiving is on, else null
 * @param restorePoints   successful, not yet purged Full and Differential backups, newest first
 * @param qdrantSnapshots successful, not yet purged Qdrant snapshots, newest first
 */
public record BackupOverviewResponse(
        boolean scheduleEnabled,
        boolean walArchivingConfigured,
        boolean postgresRestoreAvailable,
        boolean busy,
        LocalDateTime pointInTimeFrom,
        LocalDateTime pointInTimeTo,
        List<BackupRunResponse> latestRuns,
        List<BackupRunResponse> restorePoints,
        List<BackupRunResponse> qdrantSnapshots,
        PostgresRestoreStatusResponse postgresRestore) {
}
