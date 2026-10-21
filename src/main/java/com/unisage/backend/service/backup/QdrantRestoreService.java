package com.unisage.backend.service.backup;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.QdrantRestoreRequest;
import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.RestoreRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.entity.enums.RestoreType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BackupRunRepository;
import com.unisage.backend.repository.RestoreRunRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Restores one Qdrant collection from a snapshot in MinIO (ADR-0010). Qdrant recovers online, so
 * unlike Postgres this runs entirely here. The collection is snapshotted first, so the restore can
 * itself be undone from the same page.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QdrantRestoreService {

    private final BackupRunRepository backupRunRepository;
    private final RestoreRunRepository restoreRunRepository;
    private final QdrantSnapshotService qdrantSnapshotService;
    private final BackupCoordinator coordinator;
    private final BackupTaskRunner taskRunner;
    private final PostgresRestoreService postgresRestoreService;
    private final BackupRunRecorder recorder;

    public RestoreRun start(QdrantRestoreRequest request, UUID userId, String userCode) {
        BackupRun snapshot = backupRunRepository.findById(request.backupRunId())
                .orElseThrow(() -> new AppException(ErrorCode.BACKUP_RUN_NOT_FOUND));
        if (snapshot.getBackupType() != BackupType.QDRANT_SNAPSHOT || snapshot.getStatus() != BackupStatus.SUCCEEDED
                || snapshot.getPurgedAt() != null || snapshot.getTarget() == null) {
            throw new AppException(ErrorCode.BACKUP_RUN_NOT_RESTORABLE);
        }
        if (taskRunner.isBusy() || postgresRestoreService.isRestoring()) {
            throw new AppException(ErrorCode.BACKUP_TASK_BUSY);
        }
        RestoreRun run = restoreRunRepository.save(RestoreRun.builder()
                .restoreType(RestoreType.QDRANT)
                .status(BackupStatus.RUNNING)
                .sourceRunId(snapshot.getId())
                .target(snapshot.getTarget())
                .requestedBy(userId)
                .requestedByCode(userCode)
                .startedAt(recorder.nowUtc())
                .build());
        if (!taskRunner.submit("Qdrant restore " + run.getId(), () -> restore(run.getId(), snapshot))) {
            finish(run, BackupStatus.FAILED, "Another backup task started first");
            throw new AppException(ErrorCode.BACKUP_TASK_BUSY);
        }
        log.warn("Qdrant restore {} of {} from {} requested by {}", run.getId(), snapshot.getTarget(),
                snapshot.getStoragePrefix(), userCode);
        return run;
    }

    void restore(UUID id, BackupRun snapshot) {
        RestoreRun run = restoreRunRepository.findById(id).orElseThrow();
        String collection = snapshot.getTarget();
        try {
            // A collection that is gone (the usual reason to restore) has nothing to keep.
            if (qdrantSnapshotService.collections().contains(collection)) {
                BackupRun safety = coordinator.snapshotQdrantNow(List.of(collection)).get(0);
                if (safety.getStatus() != BackupStatus.SUCCEEDED) {
                    throw new BackupException("could not snapshot " + collection + " before restoring it: "
                            + safety.getErrorMessage());
                }
                run.setSafetyRunId(safety.getId());
            }
            qdrantSnapshotService.restore(collection, snapshot.getStoragePrefix());
            finish(run, BackupStatus.SUCCEEDED, null);
            log.info("Qdrant restore {} of {} succeeded", id, collection);
        } catch (RuntimeException e) {
            log.error("Qdrant restore {} of {} failed", id, collection, e);
            finish(run, BackupStatus.FAILED, BackupRunRecorder.describe(e));
        }
    }

    private void finish(RestoreRun run, BackupStatus status, String error) {
        run.setStatus(status);
        run.setFinishedAt(recorder.nowUtc());
        run.setErrorMessage(error);
        restoreRunRepository.save(run);
    }
}
