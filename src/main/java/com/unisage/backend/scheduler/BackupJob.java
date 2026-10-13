package com.unisage.backend.scheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unisage.backend.service.backup.BackupCoordinator;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * UNISAGE-102: checks every minute whether a backup is due (see {@link BackupCoordinator}). The
 * work runs on its own thread: a Full backup can take an hour, and the shared scheduler has one
 * thread that every other job needs. A tick that finds the previous one still busy is skipped,
 * so two backups never run at once.
 */
@Component
@Slf4j
public class BackupJob {

    private final BackupCoordinator backupCoordinator;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public BackupJob(BackupCoordinator backupCoordinator) {
        this(backupCoordinator, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "backup-runner");
            thread.setDaemon(true);
            return thread;
        }));
    }

    BackupJob(BackupCoordinator backupCoordinator, ExecutorService executor) {
        this.backupCoordinator = backupCoordinator;
        this.executor = executor;
    }

    @Scheduled(cron = "${app.backup.tick-cron:0 * * * * *}", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    backupCoordinator.runDueBackups();
                } catch (RuntimeException e) {
                    log.error("Backup tick failed", e);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
