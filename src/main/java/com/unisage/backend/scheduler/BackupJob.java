package com.unisage.backend.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unisage.backend.service.backup.BackupCoordinator;
import com.unisage.backend.service.backup.BackupTaskRunner;
import com.unisage.backend.service.backup.PostgresRestoreService;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-102: checks every minute whether a backup is due (see {@link BackupCoordinator}). The
 * work runs on {@link BackupTaskRunner}'s thread; a tick that finds it busy is skipped. While a
 * Postgres restore is under way the database is about to go away, so no backup starts then.
 */
@Component
@RequiredArgsConstructor
public class BackupJob {

    private final BackupCoordinator backupCoordinator;
    private final PostgresRestoreService postgresRestoreService;
    private final BackupTaskRunner taskRunner;

    @Scheduled(cron = "${app.backup.tick-cron:0 * * * * *}", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void tick() {
        taskRunner.submit("Backup tick", () -> {
            postgresRestoreService.finishCompletedJobs();
            if (!postgresRestoreService.isRestoring()) {
                backupCoordinator.runDueBackups();
            }
        });
    }
}
