package com.unisage.backend.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.unisage.backend.service.backup.BackupCoordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BackupJobTest {

    private final BackupCoordinator coordinator = mock(BackupCoordinator.class);
    private final QueueingExecutor executor = new QueueingExecutor();
    private final BackupJob job = new BackupJob(coordinator, executor);

    @Test
    void tick_whileABackupIsStillRunning_isSkipped() {
        job.tick();
        job.tick();

        assertThat(executor.queued).hasSize(1);
    }

    @Test
    void tick_afterThePreviousOneFinished_runsAgain() {
        job.tick();
        executor.runAll();
        job.tick();
        executor.runAll();

        verify(coordinator, times(2)).runDueBackups();
    }

    @Test
    void tick_afterAFailure_runsAgain() {
        doThrow(new IllegalStateException("boom")).when(coordinator).runDueBackups();

        job.tick();
        executor.runAll();
        job.tick();

        assertThat(executor.queued).hasSize(1);
    }

    /** Holds tasks until the test runs them, so "still running" is deterministic. */
    private static final class QueueingExecutor extends AbstractExecutorService {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            List<Runnable> tasks = new ArrayList<>(queued);
            queued.clear();
            tasks.forEach(Runnable::run);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
