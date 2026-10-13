package com.unisage.backend.service.backup;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.repository.BackupRunRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresBackupServiceTest {

    private final BackupRunRepository repository = mock(BackupRunRepository.class);
    private final BackupStorage storage = mock(BackupStorage.class);
    private final BackupRunRecorder recorder = mock(BackupRunRecorder.class);

    private final PostgresBackupService service = new PostgresBackupService(
            mock(BackupProperties.class), mock(PgToolRunner.class), storage, repository, recorder);

    @Test
    void retention_purgesOlderFullsWithTheirDifferentials_andWalBeforeTheOldestKeptFull() {
        BackupRun newest = full("postgres/full/3/", LocalDateTime.of(2026, 10, 11, 2, 0));
        BackupRun kept = full("postgres/full/2/", LocalDateTime.of(2026, 10, 4, 2, 0));
        BackupRun old = full("postgres/full/1/", LocalDateTime.of(2026, 9, 27, 2, 0));
        BackupRun oldDifferential = BackupRun.builder().id(UUID.randomUUID()).backupType(BackupType.DIFFERENTIAL)
                .storagePrefix("postgres/differential/1/").baseRunId(old.getId()).build();
        when(repository.findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(
                BackupType.FULL, BackupStatus.SUCCEEDED)).thenReturn(List.of(newest, kept, old));
        when(repository.findByBaseRunIdAndPurgedAtIsNull(old.getId())).thenReturn(List.of(oldDifferential));

        service.applyRetention(2);

        verify(storage).deletePrefix("postgres/full/1/");
        verify(storage).deletePrefix("postgres/differential/1/");
        verify(recorder).markPurged(old);
        verify(recorder).markPurged(oldDifferential);
        verify(storage, never()).deletePrefix("postgres/full/2/");
        verify(storage).deleteOlderThan(PostgresBackupService.WAL_PREFIX,
                kept.getStartedAt().toInstant(ZoneOffset.UTC));
    }

    @Test
    void retention_withNoFull_deletesNothing() {
        service.applyRetention(4);

        verify(storage, never()).deletePrefix(anyString());
        verify(storage, never()).deleteOlderThan(anyString(), any());
    }

    @Test
    void stamp_isSortableUtc() {
        assertThat(PostgresBackupService.stamp(LocalDateTime.of(2026, 10, 13, 2, 5, 9)))
                .isEqualTo("20261013T020509Z");
    }

    private static BackupRun full(String prefix, LocalDateTime startedAt) {
        return BackupRun.builder().id(UUID.randomUUID()).backupType(BackupType.FULL)
                .status(BackupStatus.SUCCEEDED).storagePrefix(prefix).startedAt(startedAt).build();
    }
}
