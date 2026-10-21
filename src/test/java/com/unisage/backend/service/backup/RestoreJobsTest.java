package com.unisage.backend.service.backup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.unisage.backend.service.backup.RestoreJobs.JobStatus;
import com.unisage.backend.service.backup.RestoreJobs.Phase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestoreJobsTest {

    @TempDir
    Path root;

    private final BackupProperties properties = mock(BackupProperties.class);
    private final RestoreJobs jobs = new RestoreJobs(properties);

    @BeforeEach
    void setUp() {
        when(properties.getRestoreDir()).thenReturn(root.toString());
    }

    @Test
    void isAvailable_onlyOnceTheDatabaseContainerHasWrittenItsMarker() throws Exception {
        assertThat(jobs.isConfigured()).isTrue();
        assertThat(jobs.isAvailable()).isFalse();

        Files.writeString(root.resolve(RestoreJobs.ENTRYPOINT_MARKER), "1");

        assertThat(jobs.isAvailable()).isTrue();
    }

    @Test
    void blankRestoreDir_isNeitherConfiguredNorAvailable() {
        when(properties.getRestoreDir()).thenReturn("");

        assertThat(jobs.isConfigured()).isFalse();
        assertThat(jobs.isAvailable()).isFalse();
        assertThat(jobs.all()).isEmpty();
    }

    @Test
    void status_readsWhatTheEntrypointScriptWrites() throws Exception {
        UUID id = UUID.randomUUID();
        Path dir = jobs.create(id);
        // unisage-entrypoint.sh: printf '%s\n%s\n' PHASE MESSAGE
        Files.writeString(dir.resolve("status"), "RECOVERING\nPostgres đang phát lại nhật ký giao dịch\n");
        jobs.markReady(id);

        JobStatus status = jobs.status(id).orElseThrow();

        assertThat(status.phase()).isEqualTo(Phase.RECOVERING);
        assertThat(status.message()).isEqualTo("Postgres đang phát lại nhật ký giao dịch");
        assertThat(status.ready()).isTrue();
        assertThat(status.finished()).isFalse();
        assertThat(jobs.active()).map(JobStatus::id).contains(id);
    }

    @Test
    void writeStatus_keepsTheMessageOnOneLine() {
        UUID id = UUID.randomUUID();
        jobs.create(id);

        jobs.writeStatus(id, Phase.FAILED, "pg_basebackup exited with 1\nsecond line");

        assertThat(jobs.status(id).orElseThrow().message()).isEqualTo("pg_basebackup exited with 1 second line");
    }

    @Test
    void finishedJobs_areNotActive() {
        UUID id = UUID.randomUUID();
        jobs.create(id);
        jobs.writeStatus(id, Phase.SUCCEEDED, "Khôi phục xong");
        jobs.markFinished(id);

        assertThat(jobs.active()).isEmpty();
        assertThat(jobs.status(id).orElseThrow().finished()).isTrue();
    }

    @Test
    void all_skipsFoldersThatAreNotJobs() throws Exception {
        Files.createDirectories(root.resolve("jobs").resolve("not-a-uuid"));
        UUID id = UUID.randomUUID();
        jobs.create(id);
        jobs.writeStatus(id, Phase.PREPARING, "Đang chờ bắt đầu");

        assertThat(jobs.all()).extracting(JobStatus::id).containsExactly(id);
    }

    @Test
    void deleteDownloads_keepsTheStatus() {
        UUID id = UUID.randomUUID();
        Path dir = jobs.create(id);
        jobs.writeStatus(id, Phase.FAILED, "x");

        jobs.deleteDownloads(id);

        assertThat(dir.resolve("full")).doesNotExist();
        assertThat(dir.resolve("status")).exists();
    }
}
