package com.unisage.backend.service.backup;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.repository.BackupRunRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Physical backups of the whole Postgres cluster with PG17's pg_basebackup (ADR-0008).
 *
 * <ul>
 *   <li><b>Full</b>: a plain base backup, self-contained thanks to {@code -X stream}.</li>
 *   <li><b>Differential</b>: an incremental backup always taken against the latest Full's
 *       {@code backup_manifest}, so restoring needs only that Full plus one Differential
 *       ({@code pg_combinebackup full diff}). Needs {@code summarize_wal=on} on the server.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostgresBackupService {

    static final String FULL_PREFIX = "postgres/full/";
    static final String DIFFERENTIAL_PREFIX = "postgres/differential/";
    static final String WAL_PREFIX = "postgres/wal/";
    static final String MANIFEST = "backup_manifest";

    // app.timezone wall time, e.g. 2026-10-21_09-45-58: readable in MinIO and in a downloaded
    // file's name, still sortable, and free of ':' so it is a valid file name everywhere.
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final BackupProperties properties;
    private final PgToolRunner pgToolRunner;
    private final BackupStorage storage;
    private final BackupRunRepository backupRunRepository;
    private final BackupRunRecorder recorder;

    public BackupArtifact takeFull(BackupRun run) {
        String stamp = stamp(run.getStartedAt(), properties.getZone());
        Path dir = properties.getWorkDir().resolve("full-" + stamp);
        try {
            pgToolRunner.run("pg_basebackup", baseBackupArgs(dir, "unisage-full-" + stamp));
            return upload(dir, FULL_PREFIX + stamp + "/");
        } finally {
            WorkDirs.deleteQuietly(dir);
        }
    }

    public BackupArtifact takeDifferential(BackupRun run, BackupRun full) {
        String stamp = stamp(run.getStartedAt(), properties.getZone());
        Path manifest = properties.getWorkDir().resolve("full-manifest-" + stamp);
        Path dir = properties.getWorkDir().resolve("differential-" + stamp);
        try {
            storage.download(full.getStoragePrefix() + MANIFEST, manifest);
            List<String> args = baseBackupArgs(dir, "unisage-differential-" + stamp);
            args.add("--incremental=" + manifest);
            pgToolRunner.run("pg_basebackup", args);
            return upload(dir, DIFFERENTIAL_PREFIX + stamp + "/");
        } finally {
            WorkDirs.deleteQuietly(manifest);
            WorkDirs.deleteQuietly(dir);
        }
    }

    /**
     * Keeps the newest {@code keepFulls} successful Fulls. An older Full goes together with its
     * Differentials, and WAL uploaded before the oldest kept Full started is dropped too: no kept
     * backup can replay it.
     */
    public void applyRetention(int keepFulls) {
        List<BackupRun> fulls = backupRunRepository
                .findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.FULL, BackupStatus.SUCCEEDED);
        if (fulls.isEmpty()) {
            return;
        }
        int kept = Math.min(keepFulls, fulls.size());
        for (BackupRun old : fulls.subList(kept, fulls.size())) {
            for (BackupRun differential : backupRunRepository.findByBaseRunIdAndPurgedAtIsNull(old.getId())) {
                purge(differential);
            }
            purge(old);
            log.info("Purged Full backup {} and its Differentials", old.getStoragePrefix());
        }
        BackupRun oldestKept = fulls.get(kept - 1);
        storage.deleteOlderThan(WAL_PREFIX, oldestKept.getStartedAt().toInstant(ZoneOffset.UTC));
    }

    static String stamp(LocalDateTime utc, ZoneId zone) {
        return STAMP.format(utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone));
    }

    private List<String> baseBackupArgs(Path dir, String label) {
        // Tar + gzip keeps one archive per tablespace; -X stream bundles the WAL needed to make
        // the copy consistent, so a Full restores without the WAL archive.
        return new ArrayList<>(List.of("-D", dir.toString(), "-Ft", "-z", "-X", "stream",
                "--checkpoint=fast", "--label=" + label));
    }

    private BackupArtifact upload(Path dir, String prefix) {
        try {
            return storage.uploadDirectory(dir, prefix);
        } catch (BackupException e) {
            try {
                storage.deletePrefix(prefix);
            } catch (BackupException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private void purge(BackupRun run) {
        if (run.getStoragePrefix() != null) {
            storage.deletePrefix(run.getStoragePrefix());
        }
        recorder.markPurged(run);
    }
}
