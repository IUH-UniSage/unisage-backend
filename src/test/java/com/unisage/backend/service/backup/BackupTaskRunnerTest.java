package com.unisage.backend.service.backup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackupTaskRunnerTest {

    private final QueueingExecutor executor = new QueueingExecutor();
    private final BackupTaskRunner runner = new BackupTaskRunner(executor);

    @Test
    void submit_whileATaskIsStillRunning_isRefused() {
        assertThat(runner.submit("first", () -> { })).isTrue();

        assertThat(runner.submit("second", () -> { })).isFalse();
        assertThat(runner.isBusy()).isTrue();
        assertThat(executor.queued).hasSize(1);
    }

    @Test
    void submit_afterThePreviousOneFinished_runsAgain() {
        List<String> ran = new ArrayList<>();
        runner.submit("first", () -> ran.add("first"));
        executor.runAll();
        runner.submit("second", () -> ran.add("second"));
        executor.runAll();

        assertThat(ran).containsExactly("first", "second");
        assertThat(runner.isBusy()).isFalse();
    }

    @Test
    void submit_afterAFailure_runsAgain() {
        runner.submit("boom", () -> {
            throw new IllegalStateException("boom");
        });
        executor.runAll();

        assertThat(runner.submit("next", () -> { })).isTrue();
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
