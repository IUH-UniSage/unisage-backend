package com.unisage.backend.service.backup;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.repository.BackupRunRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Writes the {@code backup_runs} history: one row per run, RUNNING until it ends either way. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackupRunRecorder {

    static final int MAX_ERROR_CHARS = 4000;

    private final BackupRunRepository backupRunRepository;
    private final Clock clock;

    public BackupRun start(BackupType type, String target, LocalDateTime scheduledFor, UUID baseRunId) {
        return backupRunRepository.save(BackupRun.builder()
                .backupType(type)
                .status(BackupStatus.RUNNING)
                .target(target)
                .scheduledFor(scheduledFor)
                .startedAt(nowUtc())
                .baseRunId(baseRunId)
                .build());
    }

    public BackupRun succeed(BackupRun run, BackupArtifact artifact) {
        run.setStatus(BackupStatus.SUCCEEDED);
        run.setFinishedAt(nowUtc());
        run.setStoragePrefix(artifact.storagePrefix());
        run.setFileCount(artifact.fileCount());
        run.setSizeBytes(artifact.sizeBytes());
        run.setChecksum(artifact.checksum());
        return backupRunRepository.save(run);
    }

    public BackupRun fail(BackupRun run, Exception error) {
        log.error("{} backup{} failed", run.getBackupType(),
                run.getTarget() == null ? "" : " of " + run.getTarget(), error);
        run.setStatus(BackupStatus.FAILED);
        run.setFinishedAt(nowUtc());
        run.setErrorMessage(describe(error));
        return backupRunRepository.save(run);
    }

    public void markPurged(BackupRun run) {
        run.setPurgedAt(nowUtc());
        backupRunRepository.save(run);
    }

    /** A restart kills a backup mid-way; its row would otherwise stay RUNNING forever. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        List<BackupRun> interrupted = backupRunRepository.findByStatus(BackupStatus.RUNNING);
        for (BackupRun run : interrupted) {
            run.setStatus(BackupStatus.FAILED);
            run.setFinishedAt(nowUtc());
            run.setErrorMessage("Interrupted by an application restart");
        }
        if (!interrupted.isEmpty()) {
            backupRunRepository.saveAll(interrupted);
            log.warn("Marked {} interrupted backup run(s) as FAILED", interrupted.size());
        }
    }

    public LocalDateTime nowUtc() {
        return LocalDateTime.now(clock);
    }

    static String describe(Exception error) {
        StringBuilder message = new StringBuilder(String.valueOf(error.getMessage()));
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            message.append(" <- ").append(cause.getMessage());
        }
        return message.length() <= MAX_ERROR_CHARS ? message.toString() : message.substring(0, MAX_ERROR_CHARS);
    }
}
