package com.unisage.backend.service.backup;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The one thread that backups and restores run on (UNISAGE-102). A Full backup can take an hour
 * and the shared scheduler has one thread that every other job needs, so the work runs here; and
 * since there is only one, a scheduled backup, a manual one and a restore never overlap.
 */
@Component
@Slf4j
public class BackupTaskRunner {

    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public BackupTaskRunner() {
        this(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "backup-runner");
            thread.setDaemon(true);
            return thread;
        }));
    }

    BackupTaskRunner(ExecutorService executor) {
        this.executor = executor;
    }

    /** Starts {@code task} unless another one is still running; returns whether it started. */
    public boolean submit(String name, Runnable task) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.error("{} failed", name, e);
                } finally {
                    running.set(false);
                }
            });
            return true;
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    public boolean isBusy() {
        return running.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
