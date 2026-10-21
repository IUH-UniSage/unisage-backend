package com.unisage.backend.service.backup;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.response.BackupFileResponse;
import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BackupRunRepository;
import com.unisage.backend.repository.RestoreRunRepository;
import com.unisage.backend.service.backup.BackupStorage.StoredObject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupAdminServiceTest {

    private final BackupRunRepository repository = mock(BackupRunRepository.class);
    private final BackupStorage storage = mock(BackupStorage.class);

    private final BackupAdminService service = new BackupAdminService(repository,
            mock(RestoreRunRepository.class), mock(BackupSettingsReader.class), mock(BackupCoordinator.class),
            mock(BackupTaskRunner.class), mock(PostgresRestoreService.class), mock(QdrantSnapshotService.class),
            mock(RestoreJobs.class), mock(BackupRunRecorder.class), storage);

    @Test
    void files_ofAFull_listsItsFolderWithReadableDownloadNames() {
        String prefix = "postgres/full/2026-10-21_09-45-58/";
        BackupRun run = givenRun(BackupType.FULL, prefix);
        when(storage.list(prefix)).thenReturn(List.of(
                new StoredObject(prefix + "pg_wal.tar.gz", Instant.parse("2026-10-21T02:46:10Z"), 20),
                new StoredObject(prefix + "base.tar.gz", Instant.parse("2026-10-21T02:46:05Z"), 100)));
        when(storage.downloadUrl(anyString(), anyString())).thenAnswer(call -> "https://minio/" + call.getArgument(1));

        List<BackupFileResponse> files = service.files(run.getId());

        assertThat(files).extracting(BackupFileResponse::name).containsExactly("base.tar.gz", "pg_wal.tar.gz");
        assertThat(files.get(0).downloadName()).isEqualTo("full_2026-10-21_09-45-58_base.tar.gz");
        assertThat(files.get(0).downloadUrl()).isEqualTo("https://minio/full_2026-10-21_09-45-58_base.tar.gz");
        assertThat(files.get(0).sizeBytes()).isEqualTo(100);
    }

    @Test
    void files_ofAWalRun_listsOnlyTheSegmentsItUploaded() {
        BackupRun run = givenRun(BackupType.WAL, PostgresBackupService.WAL_PREFIX);
        run.setStartedAt(LocalDateTime.of(2026, 10, 21, 2, 0, 0, 500_000_000));
        run.setFinishedAt(LocalDateTime.of(2026, 10, 21, 2, 0, 3));
        when(storage.list(PostgresBackupService.WAL_PREFIX)).thenReturn(List.of(
                new StoredObject("postgres/wal/000000010000000000000001", Instant.parse("2026-10-21T01:55:00Z"), 16),
                new StoredObject("postgres/wal/000000010000000000000002", Instant.parse("2026-10-21T02:00:00Z"), 16),
                new StoredObject("postgres/wal/000000010000000000000003", Instant.parse("2026-10-21T02:00:04Z"), 16),
                new StoredObject("postgres/wal/000000010000000000000004", Instant.parse("2026-10-21T02:05:00Z"), 16)));

        assertThat(service.files(run.getId())).extracting(BackupFileResponse::name)
                .containsExactly("000000010000000000000002", "000000010000000000000003");
    }

    @Test
    void files_ofAPurgedRun_isEmptyWithoutAskingMinio() {
        BackupRun run = givenRun(BackupType.FULL, "postgres/full/x/");
        run.setPurgedAt(LocalDateTime.of(2026, 10, 22, 0, 0));

        assertThat(service.files(run.getId())).isEmpty();
        verify(storage, never()).list(anyString());
    }

    @Test
    void files_whenMinioFails_isAFriendlyError() {
        BackupRun run = givenRun(BackupType.FULL, "postgres/full/x/");
        when(storage.list("postgres/full/x/")).thenThrow(new BackupException("down"));

        assertThatThrownBy(() -> service.files(run.getId()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BACKUP_FILES_UNAVAILABLE));
    }

    @Test
    void names_ofAQdrantSnapshot_areItsOwnName() {
        String key = "qdrant/unisage_chunks/unisage_chunks_2026-10-21_09-45-58.snapshot";

        assertThat(BackupAdminService.fileName(key, key)).isEqualTo("unisage_chunks_2026-10-21_09-45-58.snapshot");
        assertThat(BackupAdminService.downloadName(BackupType.QDRANT_SNAPSHOT, key))
                .isEqualTo("unisage_chunks_2026-10-21_09-45-58.snapshot");
    }

    private BackupRun givenRun(BackupType type, String prefix) {
        BackupRun run = BackupRun.builder().id(UUID.randomUUID()).backupType(type).status(BackupStatus.SUCCEEDED)
                .storagePrefix(prefix).startedAt(LocalDateTime.of(2026, 10, 21, 2, 45, 58))
                .finishedAt(LocalDateTime.of(2026, 10, 21, 2, 46, 30)).build();
        when(repository.findById(run.getId())).thenReturn(Optional.of(run));
        return run;
    }
}
