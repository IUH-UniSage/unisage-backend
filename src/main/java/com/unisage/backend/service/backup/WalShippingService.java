package com.unisage.backend.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * The Transaction Log part of the backup set (ADR-0008). Postgres copies each finished WAL
 * segment into the shared archive folder through {@code archive_command}; this ships those files
 * to object storage and removes them locally. With a Full (plus Differential) and the WAL after
 * it, the database can be restored to any point in time (PITR).
 */
@Service
@RequiredArgsConstructor
public class WalShippingService {

    private static final Duration ARCHIVE_WAIT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 500;

    private final BackupProperties properties;
    private final BackupStorage storage;
    private final JdbcTemplate jdbcTemplate;

    public BackupArtifact ship() {
        String archiveDir = properties.getWalArchiveDir();
        if (archiveDir == null || archiveDir.isBlank()) {
            throw new BackupException("app.backup.wal-archive-dir is not set");
        }
        Path archive = Path.of(archiveDir);

        // Close the current segment so the newest changes are archived now, not when the
        // segment fills up or archive_timeout fires. Needs superuser (or a GRANT).
        String closed = jdbcTemplate.queryForObject("SELECT pg_walfile_name(pg_switch_wal())", String.class);
        awaitArchived(archive.resolve(closed));

        long size = 0;
        List<Path> files = archivedFiles(archive);
        for (Path file : files) {
            size += storage.uploadFile(file, PostgresBackupService.WAL_PREFIX + file.getFileName());
            try {
                Files.delete(file);
            } catch (IOException e) {
                throw new BackupException("cannot remove shipped WAL file " + file, e);
            }
        }
        return new BackupArtifact(PostgresBackupService.WAL_PREFIX, files.size(), size, null);
    }

    /**
     * With no writes since the last switch Postgres has nothing to close and the named file was
     * shipped already, so this waits a little and then ships whatever is there.
     */
    private static void awaitArchived(Path segment) {
        long deadline = System.nanoTime() + ARCHIVE_WAIT.toNanos();
        while (!Files.exists(segment) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BackupException("interrupted while waiting for " + segment.getFileName(), e);
            }
        }
    }

    /** archive_command writes {@code <name>.tmp} then renames it, so a .tmp file is still being copied. */
    private static List<Path> archivedFiles(Path archive) {
        try (Stream<Path> listing = Files.list(archive)) {
            return listing
                    .filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().endsWith(".tmp"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new BackupException("cannot list WAL archive " + archive, e);
        }
    }
}
