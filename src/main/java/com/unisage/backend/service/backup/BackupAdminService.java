package com.unisage.backend.service.backup;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.response.BackupOverviewResponse;
import com.unisage.backend.dto.response.BackupRunResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.PostgresRestoreStatusResponse;
import com.unisage.backend.dto.response.RestoreRunResponse;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BackupRunRepository;
import com.unisage.backend.repository.RestoreRunRepository;

import lombok.RequiredArgsConstructor;

/** Read side of the "Sao lưu & khôi phục" page, plus the "Sao lưu ngay" button (UNISAGE-102). */
@Service
@RequiredArgsConstructor
public class BackupAdminService {

    private final BackupRunRepository backupRunRepository;
    private final RestoreRunRepository restoreRunRepository;
    private final BackupSettingsReader settingsReader;
    private final BackupCoordinator coordinator;
    private final BackupTaskRunner taskRunner;
    private final PostgresRestoreService postgresRestoreService;
    private final QdrantSnapshotService qdrantSnapshotService;
    private final RestoreJobs restoreJobs;
    private final BackupRunRecorder recorder;

    public BackupOverviewResponse overview() {
        boolean walConfigured = coordinator.walArchivingConfigured();
        List<BackupRunResponse> latest = Arrays.stream(BackupType.values())
                .map(backupRunRepository::findFirstByBackupTypeOrderByStartedAtDesc)
                .flatMap(Optional::stream)
                .map(BackupRunResponse::from)
                .toList();
        return new BackupOverviewResponse(
                settingsReader.read().enabled(),
                walConfigured,
                restoreJobs.isAvailable(),
                taskRunner.isBusy() || postgresRestoreService.isRestoring(),
                walConfigured ? postgresRestoreService.pointInTimeFrom().orElse(null) : null,
                walConfigured ? recorder.nowUtc() : null,
                latest,
                postgresRestoreService.restorePoints().stream().map(BackupRunResponse::from).toList(),
                backupRunRepository.findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(
                        BackupType.QDRANT_SNAPSHOT, BackupStatus.SUCCEEDED).stream().map(BackupRunResponse::from).toList(),
                postgresRestoreStatus().orElse(null));
    }

    public Optional<PostgresRestoreStatusResponse> postgresRestoreStatus() {
        return postgresRestoreService.latestStatus().map(PostgresRestoreStatusResponse::from);
    }

    public PageResponse<List<BackupRunResponse>> runs(BackupType type, Pageable pageable) {
        return PageResponse.fromPage(type == null
                ? backupRunRepository.findAllByOrderByStartedAtDesc(pageable)
                : backupRunRepository.findByBackupTypeOrderByStartedAtDesc(type, pageable), BackupRunResponse::from);
    }

    public PageResponse<List<RestoreRunResponse>> restores(Pageable pageable) {
        return PageResponse.fromPage(restoreRunRepository.findAllByOrderByStartedAtDesc(pageable),
                RestoreRunResponse::from);
    }

    /** Starts one backup of {@code type} in the background; the history shows how it went. */
    public void runNow(BackupType type) {
        boolean runnable = switch (type) {
            case FULL, QDRANT_SNAPSHOT -> true;
            case DIFFERENTIAL -> backupRunRepository
                    .findFirstByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.FULL, BackupStatus.SUCCEEDED)
                    .isPresent();
            case WAL -> coordinator.walArchivingConfigured();
        };
        if (!runnable) {
            throw new AppException(ErrorCode.BACKUP_TYPE_NOT_MANUAL);
        }
        if (postgresRestoreService.isRestoring()) {
            throw new AppException(ErrorCode.BACKUP_TASK_BUSY);
        }
        boolean started = taskRunner.submit("Manual " + type + " backup", () -> {
            switch (type) {
                case FULL -> coordinator.runFullNow();
                case DIFFERENTIAL -> coordinator.runDifferentialNow();
                case WAL -> coordinator.shipWalNow();
                case QDRANT_SNAPSHOT -> coordinator.snapshotQdrantNow(qdrantSnapshotService.collections());
            }
        });
        if (!started) {
            throw new AppException(ErrorCode.BACKUP_TASK_BUSY);
        }
    }
}
