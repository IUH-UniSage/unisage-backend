package com.unisage.backend.service.backup;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.request.QdrantRestoreRequest;
import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.RestoreRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.entity.enums.RestoreType;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BackupRunRepository;
import com.unisage.backend.repository.RestoreRunRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantRestoreServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0);

    private final BackupRunRepository backupRuns = mock(BackupRunRepository.class);
    private final RestoreRunRepository restoreRuns = mock(RestoreRunRepository.class);
    private final QdrantSnapshotService snapshots = mock(QdrantSnapshotService.class);
    private final BackupCoordinator coordinator = mock(BackupCoordinator.class);
    private final BackupTaskRunner taskRunner = mock(BackupTaskRunner.class);
    private final PostgresRestoreService postgresRestore = mock(PostgresRestoreService.class);
    private final BackupRunRecorder recorder = mock(BackupRunRecorder.class);

    private final QdrantRestoreService service = new QdrantRestoreService(backupRuns, restoreRuns, snapshots,
            coordinator, taskRunner, postgresRestore, recorder);

    private final BackupRun snapshot = BackupRun.builder().id(UUID.randomUUID())
            .backupType(BackupType.QDRANT_SNAPSHOT).status(BackupStatus.SUCCEEDED).target("unisage_chunks")
            .storagePrefix("qdrant/unisage_chunks/a.snapshot").build();
    private final RestoreRun run = RestoreRun.builder().id(UUID.randomUUID()).restoreType(RestoreType.QDRANT)
            .status(BackupStatus.RUNNING).build();

    @BeforeEach
    void setUp() {
        when(recorder.nowUtc()).thenReturn(NOW);
        when(restoreRuns.findById(run.getId())).thenReturn(Optional.of(run));
    }

    @Test
    void start_aPurgedSnapshot_isNotRestorable() {
        snapshot.setPurgedAt(NOW);
        when(backupRuns.findById(snapshot.getId())).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.start(new QdrantRestoreRequest(snapshot.getId()), null, "SA-001"))
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_RUN_NOT_RESTORABLE);
        verify(restoreRuns, never()).save(any());
    }

    @Test
    void restore_snapshotsTheCurrentCollectionFirst() {
        BackupRun safety = BackupRun.builder().id(UUID.randomUUID()).status(BackupStatus.SUCCEEDED).build();
        when(snapshots.collections()).thenReturn(List.of("unisage_chunks"));
        when(coordinator.snapshotQdrantNow(List.of("unisage_chunks"))).thenReturn(List.of(safety));

        service.restore(run.getId(), snapshot);

        verify(snapshots).restore("unisage_chunks", "qdrant/unisage_chunks/a.snapshot");
        assertThat(run.getSafetyRunId()).isEqualTo(safety.getId());
        assertThat(run.getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
    }

    @Test
    void restore_aMissingCollection_isRecreatedWithoutASafetySnapshot() {
        when(snapshots.collections()).thenReturn(List.of());

        service.restore(run.getId(), snapshot);

        verify(coordinator, never()).snapshotQdrantNow(any());
        verify(snapshots).restore("unisage_chunks", "qdrant/unisage_chunks/a.snapshot");
        assertThat(run.getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
    }

    @Test
    void restore_whenTheSafetySnapshotFails_leavesTheCollectionAlone() {
        BackupRun safety = BackupRun.builder().id(UUID.randomUUID()).status(BackupStatus.FAILED)
                .errorMessage("disk full").build();
        when(snapshots.collections()).thenReturn(List.of("unisage_chunks"));
        when(coordinator.snapshotQdrantNow(List.of("unisage_chunks"))).thenReturn(List.of(safety));

        service.restore(run.getId(), snapshot);

        verify(snapshots, never()).restore(any(), any());
        assertThat(run.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("disk full");
    }

    @Test
    void restore_aQdrantError_isRecorded() {
        when(snapshots.collections()).thenReturn(List.of());
        doThrow(new BackupException("Qdrant could not recover unisage_chunks")).when(snapshots)
                .restore(any(), any());

        service.restore(run.getId(), snapshot);

        assertThat(run.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("could not recover");
    }
}
