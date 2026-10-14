package com.unisage.backend.service.backup;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.repository.BackupRunRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Decides which backups are due and runs them one after another (UNISAGE-102). Settings are read
 * on every tick, so an admin edit applies from the next minute on.
 *
 * <p>A scheduled type (Full, Differential, Qdrant) is due when the latest slot of its frequency
 * is newer than the slot its last run answered. A failed run still answers its slot: it is not
 * retried every minute, the next slot tries again. Enabling backups therefore takes a Full right
 * away (the last slot has no run yet), which every Differential then needs.
 */
@Service
@Slf4j
public class BackupCoordinator {

    private final BackupSettingsReader settingsReader;
    private final BackupRunRepository backupRunRepository;
    private final BackupRunRecorder recorder;
    private final PostgresBackupService postgresBackupService;
    private final WalShippingService walShippingService;
    private final QdrantSnapshotService qdrantSnapshotService;
    private final BackupProperties properties;
    private final Clock clock;
    private final ZoneId zone;

    private boolean walUnconfiguredLogged;

    public BackupCoordinator(BackupSettingsReader settingsReader, BackupRunRepository backupRunRepository,
            BackupRunRecorder recorder, PostgresBackupService postgresBackupService,
            WalShippingService walShippingService, QdrantSnapshotService qdrantSnapshotService,
            BackupProperties properties, Clock clock,
            @Value("${app.timezone:Asia/Ho_Chi_Minh}") String timezone) {
        this.settingsReader = settingsReader;
        this.backupRunRepository = backupRunRepository;
        this.recorder = recorder;
        this.postgresBackupService = postgresBackupService;
        this.walShippingService = walShippingService;
        this.qdrantSnapshotService = qdrantSnapshotService;
        this.properties = properties;
        this.clock = clock;
        this.zone = ZoneId.of(timezone);
    }

    public void runDueBackups() {
        BackupSettings settings = settingsReader.read();
        if (!settings.enabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(zone));

        dueSlot(BackupType.FULL, settings.fullFrequency().latestSlot(now, settings.fullTime()))
                .ifPresent(slot -> runFull(slot, settings.fullRetentionCount()));
        dueSlot(BackupType.DIFFERENTIAL, settings.differentialFrequency().latestSlot(now, settings.differentialTime()))
                .ifPresent(this::runDifferential);
        if (walDue(settings.walIntervalMinutes())) {
            run(BackupType.WAL, null, now.truncatedTo(ChronoUnit.MINUTES), null, current -> walShippingService.ship());
        }
        dueSlot(BackupType.QDRANT_SNAPSHOT, settings.qdrantFrequency().latestSlot(now, settings.qdrantTime()))
                .ifPresent(slot -> runQdrant(slot, settings.qdrantRetentionCount()));
    }

    /**
     * A Full outside the schedule: the "Sao lưu ngay" button, or the safety copy taken before a
     * restore. Retention is left to the scheduled runs so this never purges a backup a restore is
     * about to use. Returns the recorded run, SUCCEEDED or FAILED.
     */
    public BackupRun runFullNow() {
        BackupRun run = recorder.start(BackupType.FULL, null, nowSlot(), null);
        finish(run, () -> postgresBackupService.takeFull(run));
        return run;
    }

    public BackupRun runDifferentialNow() {
        BackupRun base = backupRunRepository
                .findFirstByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.FULL, BackupStatus.SUCCEEDED)
                .orElseThrow(() -> new BackupException("there is no successful Full backup to take a Differential against"));
        BackupRun run = recorder.start(BackupType.DIFFERENTIAL, null, nowSlot(), base.getId());
        finish(run, () -> postgresBackupService.takeDifferential(run, base));
        return run;
    }

    public BackupRun shipWalNow() {
        BackupRun run = recorder.start(BackupType.WAL, null, nowSlot(), null);
        finish(run, walShippingService::ship);
        return run;
    }

    public boolean walArchivingConfigured() {
        String archiveDir = properties.getWalArchiveDir();
        return archiveDir != null && !archiveDir.isBlank();
    }

    /** Snapshots the given collections now; retention is left to the scheduled runs. */
    public List<BackupRun> snapshotQdrantNow(List<String> collections) {
        LocalDateTime slot = nowSlot();
        return collections.stream().map(collection -> {
            BackupRun run = recorder.start(BackupType.QDRANT_SNAPSHOT, collection, slot, null);
            finish(run, () -> qdrantSnapshotService.snapshot(collection));
            return run;
        }).toList();
    }

    private LocalDateTime nowSlot() {
        return LocalDateTime.now(clock.withZone(zone)).truncatedTo(ChronoUnit.MINUTES);
    }

    private Optional<LocalDateTime> dueSlot(BackupType type, Optional<LocalDateTime> latestSlot) {
        return latestSlot.filter(slot -> backupRunRepository
                .findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(type)
                .map(last -> slot.isAfter(last.getScheduledFor()))
                .orElse(true));
    }

    private void runFull(LocalDateTime slot, int keepFulls) {
        BackupRun run = recorder.start(BackupType.FULL, null, slot, null);
        if (finish(run, () -> postgresBackupService.takeFull(run))) {
            guard("Full backup retention", () -> postgresBackupService.applyRetention(keepFulls));
        }
    }

    private void runDifferential(LocalDateTime slot) {
        Optional<BackupRun> full = backupRunRepository
                .findFirstByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.FULL, BackupStatus.SUCCEEDED);
        if (full.isEmpty()) {
            // Not recorded: the slot stays due and runs once a Full exists.
            log.info("Skipping the Differential backup for {}: there is no successful Full yet", slot);
            return;
        }
        BackupRun base = full.get();
        run(BackupType.DIFFERENTIAL, null, slot, base,
                current -> postgresBackupService.takeDifferential(current, base));
    }

    private boolean walDue(int intervalMinutes) {
        if (intervalMinutes <= 0) {
            return false;
        }
        if (!walArchivingConfigured()) {
            if (!walUnconfiguredLogged) {
                log.warn("Transaction log backups are on but app.backup.wal-archive-dir is not set; skipping them");
                walUnconfiguredLogged = true;
            }
            return false;
        }
        LocalDateTime cutoff = recorder.nowUtc().minusMinutes(intervalMinutes);
        return backupRunRepository.findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(BackupType.WAL)
                .map(last -> !last.getStartedAt().isAfter(cutoff))
                .orElse(true);
    }

    private void runQdrant(LocalDateTime slot, int keepPerCollection) {
        List<String> collections;
        try {
            collections = qdrantSnapshotService.collections();
        } catch (BackupException e) {
            // Recorded so the slot counts as answered and the failure shows in the history.
            recorder.fail(recorder.start(BackupType.QDRANT_SNAPSHOT, null, slot, null), e);
            return;
        }
        for (String collection : collections) {
            if (run(BackupType.QDRANT_SNAPSHOT, collection, slot, null,
                    current -> qdrantSnapshotService.snapshot(collection))) {
                guard("Qdrant snapshot retention for " + collection,
                        () -> qdrantSnapshotService.applyRetention(collection, keepPerCollection));
            }
        }
    }

    private boolean run(BackupType type, String target, LocalDateTime slot, BackupRun base,
            Function<BackupRun, BackupArtifact> work) {
        BackupRun run = recorder.start(type, target, slot, base == null ? null : base.getId());
        return finish(run, () -> work.apply(run));
    }

    private boolean finish(BackupRun run, Supplier<BackupArtifact> work) {
        try {
            BackupArtifact artifact = work.get();
            recorder.succeed(run, artifact);
            log.info("{} backup{} stored at {} ({} file(s), {} bytes)", run.getBackupType(),
                    run.getTarget() == null ? "" : " of " + run.getTarget(),
                    artifact.storagePrefix(), artifact.fileCount(), artifact.sizeBytes());
            return true;
        } catch (RuntimeException e) {
            recorder.fail(run, e);
            return false;
        }
    }

    private static void guard(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("{} failed", step, e);
        }
    }
}
