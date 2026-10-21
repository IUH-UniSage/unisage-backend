package com.unisage.backend.service.backup;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.response.BackupFileResponse;
import com.unisage.backend.dto.response.BackupOverviewResponse;
import com.unisage.backend.dto.response.BackupRunResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.PostgresRestoreStatusResponse;
import com.unisage.backend.dto.response.RestoreRunResponse;
import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BackupRunRepository;
import com.unisage.backend.repository.RestoreRunRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Read side of the "Sao lưu & khôi phục" page, plus the "Sao lưu ngay" button (UNISAGE-102). */
@Service
@RequiredArgsConstructor
@Slf4j
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
    private final BackupStorage storage;

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

    /**
     * What a successful, not yet purged run left in MinIO, each with a download link. Transaction
     * log runs share one folder, so a WAL run lists only the segments uploaded while it ran.
     */
    public List<BackupFileResponse> files(UUID runId) {
        BackupRun run = backupRunRepository.findById(runId)
                .orElseThrow(() -> new AppException(ErrorCode.BACKUP_RUN_NOT_FOUND));
        if (run.getStatus() != BackupStatus.SUCCEEDED || run.getPurgedAt() != null
                || run.getStoragePrefix() == null) {
            return List.of();
        }
        String prefix = run.getStoragePrefix();
        try {
            Stream<BackupStorage.StoredObject> objects = storage.list(prefix).stream();
            if (run.getBackupType() == BackupType.WAL) {
                // MinIO keeps whole seconds; the run's own timestamps have fractions.
                Instant from = run.getStartedAt().truncatedTo(ChronoUnit.SECONDS).toInstant(ZoneOffset.UTC);
                Instant to = run.getFinishedAt().plusSeconds(1).toInstant(ZoneOffset.UTC);
                objects = objects.filter(o -> !o.lastModified().isBefore(from) && !o.lastModified().isAfter(to));
            }
            return objects
                    .sorted(Comparator.comparing(BackupStorage.StoredObject::key))
                    .map(o -> {
                        String downloadName = downloadName(run.getBackupType(), o.key());
                        return new BackupFileResponse(fileName(prefix, o.key()), o.key(), o.size(),
                                LocalDateTime.ofInstant(o.lastModified(), ZoneOffset.UTC), downloadName,
                                storage.downloadUrl(o.key(), downloadName));
                    })
                    .toList();
        } catch (BackupException e) {
            log.warn("Cannot list the files of backup run {}: {}", runId, e.getMessage());
            throw new AppException(ErrorCode.BACKUP_FILES_UNAVAILABLE);
        }
    }

    /** The path under the run's folder; a snapshot's prefix is its own key, so just its name. */
    static String fileName(String prefix, String key) {
        return key.length() > prefix.length() && key.startsWith(prefix)
                ? key.substring(prefix.length())
                : key.substring(key.lastIndexOf('/') + 1);
    }

    /**
     * A name that still says what the file is once it sits in a Downloads folder, e.g.
     * {@code full_2026-10-21_09-45-58_base.tar.gz}. A snapshot's own name already does.
     */
    static String downloadName(BackupType type, String key) {
        if (type == BackupType.QDRANT_SNAPSHOT) {
            return key.substring(key.lastIndexOf('/') + 1);
        }
        // Drop the "postgres/" root: full/<time>/base.tar.gz -> full_<time>_base.tar.gz
        return key.substring(key.indexOf('/') + 1).replace('/', '_');
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
