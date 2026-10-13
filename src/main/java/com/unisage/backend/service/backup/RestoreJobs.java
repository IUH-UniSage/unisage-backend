package com.unisage.backend.service.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * The files a Postgres restore is handed over through (ADR-0010). Each job is a folder
 * {@code <restore-dir>/jobs/<restore run id>/} holding the downloaded backup ({@code full/},
 * {@code diff/}, {@code wal/}), the recovery {@code target}, the history to carry over, and a
 * {@code status} file (phase, then a message) written first by the backend and, once
 * {@code READY} exists, by docker/postgres/unisage-entrypoint.sh.
 *
 * <p>Read without the database on purpose: while a restore runs, Postgres is down.
 */
@Component
@RequiredArgsConstructor
public class RestoreJobs {

    /** Written by unisage-entrypoint.sh on start, so its presence means restores are supported. */
    static final String ENTRYPOINT_MARKER = ".unisage-entrypoint";

    public enum Phase {
        /** The backend is downloading the backup. */
        PREPARING,
        /** The database container is replacing the data directory. */
        APPLYING,
        /** Postgres is replaying WAL up to the target. */
        RECOVERING,
        SUCCEEDED,
        FAILED;

        public boolean isActive() {
            return this == PREPARING || this == APPLYING || this == RECOVERING;
        }
    }

    public record JobStatus(UUID id, Phase phase, String message, Instant updatedAt, boolean ready,
            boolean finished) {
    }

    private final BackupProperties properties;

    /** The restore folder is configured and the database container runs unisage-entrypoint.sh. */
    public boolean isAvailable() {
        return jobsRoot().isPresent() && Files.exists(root().resolve(ENTRYPOINT_MARKER));
    }

    public boolean isConfigured() {
        return jobsRoot().isPresent();
    }

    public Path create(UUID id) {
        Path dir = dir(id);
        try {
            Files.createDirectories(dir.resolve("full"));
            Files.createDirectories(dir.resolve("wal"));
        } catch (IOException e) {
            throw new BackupException("cannot create restore folder " + dir, e);
        }
        return dir;
    }

    public Path dir(UUID id) {
        return jobsRoot().orElseThrow(() -> new BackupException("app.backup.restore-dir is not set"))
                .resolve(id.toString());
    }

    public void writeStatus(UUID id, Phase phase, String message) {
        write(id, "status", phase.name() + "\n" + (message == null ? "" : message.replace('\n', ' ')) + "\n");
    }

    public void writeTarget(UUID id, String target) {
        write(id, "target", target + "\n");
    }

    public void writeCarryOver(UUID id, String json) {
        write(id, "carry-over.json", json);
    }

    public boolean hasCarryOver(UUID id) {
        return Files.exists(dir(id).resolve("carry-over.json"));
    }

    public String readCarryOver(UUID id) {
        try {
            return Files.readString(dir(id).resolve("carry-over.json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BackupException("cannot read the history saved for restore " + id, e);
        }
    }

    /** Hands the job to the database container. From here on Postgres may stop at any moment. */
    public void markReady(UUID id) {
        write(id, "READY", "");
    }

    /** The backend has written the job's outcome back into the database. */
    public void markFinished(UUID id) {
        write(id, "FINISHED", "");
    }

    /** Drops the downloaded backup of a job that never reached the database container. */
    public void deleteDownloads(UUID id) {
        for (String child : List.of("full", "diff", "wal")) {
            WorkDirs.deleteQuietly(dir(id).resolve(child));
        }
    }

    public Optional<JobStatus> status(UUID id) {
        Path dir = dir(id);
        Path status = dir.resolve("status");
        if (!Files.exists(status)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(status, StandardCharsets.UTF_8);
            Phase phase = Phase.valueOf(lines.get(0).trim());
            String message = lines.size() > 1 ? lines.get(1) : "";
            return Optional.of(new JobStatus(id, phase, message, Files.getLastModifiedTime(status).toInstant(),
                    Files.exists(dir.resolve("READY")), Files.exists(dir.resolve("FINISHED"))));
        } catch (IOException | RuntimeException e) {
            // Mid-rename by the database container, or a stray folder.
            return Optional.empty();
        }
    }

    /** Every job, newest first. */
    public List<JobStatus> all() {
        Optional<Path> root = jobsRoot();
        if (root.isEmpty() || !Files.isDirectory(root.get())) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root.get())) {
            return dirs.filter(Files::isDirectory)
                    .map(dir -> parseId(dir.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .map(this::status)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(JobStatus::updatedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new BackupException("cannot list restore jobs", e);
        }
    }

    public Optional<JobStatus> active() {
        return all().stream().filter(job -> job.phase().isActive()).findFirst();
    }

    private void write(UUID id, String name, String content) {
        Path dir = dir(id);
        Path tmp = dir.resolve(name + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BackupException("cannot write " + name + " for restore " + id, e);
        }
    }

    private Path root() {
        return Path.of(properties.getRestoreDir());
    }

    private Optional<Path> jobsRoot() {
        String dir = properties.getRestoreDir();
        return dir == null || dir.isBlank() ? Optional.empty() : Optional.of(Path.of(dir).resolve("jobs"));
    }

    private static Optional<UUID> parseId(String name) {
        try {
            return Optional.of(UUID.fromString(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
