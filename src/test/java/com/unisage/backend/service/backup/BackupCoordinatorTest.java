package com.unisage.backend.service.backup;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.repository.BackupRunRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BackupCoordinatorTest {

    // 09:00 on Tuesday 2026-10-13 in Asia/Ho_Chi_Minh.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T02:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 10, 13, 2, 0);
    private static final LocalDateTime LAST_SUNDAY_2AM = LocalDateTime.of(2026, 10, 11, 2, 0);
    private static final LocalDateTime TODAY_3AM = LocalDateTime.of(2026, 10, 13, 3, 0);

    private final BackupSettingsReader settingsReader = mock(BackupSettingsReader.class);
    private final BackupRunRepository repository = mock(BackupRunRepository.class);
    private final BackupRunRecorder recorder = mock(BackupRunRecorder.class);
    private final PostgresBackupService postgres = mock(PostgresBackupService.class);
    private final WalShippingService wal = mock(WalShippingService.class);
    private final QdrantSnapshotService qdrant = mock(QdrantSnapshotService.class);
    private final BackupProperties properties = mock(BackupProperties.class);

    private final BackupCoordinator coordinator = new BackupCoordinator(settingsReader, repository, recorder,
            postgres, wal, qdrant, properties, CLOCK, "Asia/Ho_Chi_Minh");

    private static final BackupArtifact ARTIFACT = new BackupArtifact("prefix/", 1, 10, null);

    @BeforeEach
    void setUp() {
        when(recorder.nowUtc()).thenReturn(NOW_UTC);
        when(recorder.start(any(), any(), any(), any())).thenAnswer(call -> BackupRun.builder()
                .id(UUID.randomUUID())
                .backupType(call.getArgument(0))
                .target(call.getArgument(1))
                .scheduledFor(call.getArgument(2))
                .baseRunId(call.getArgument(3))
                .startedAt(NOW_UTC)
                .status(BackupStatus.RUNNING)
                .build());
        when(properties.getWalArchiveDir()).thenReturn("/wal-archive");
        when(qdrant.collections()).thenReturn(List.of());
    }

    @Test
    void disabled_doesNothing() {
        when(settingsReader.read()).thenReturn(settings(false, BackupFrequency.OFF, 0, BackupFrequency.OFF));

        coordinator.runDueBackups();

        verifyNoInteractions(repository, recorder, postgres, wal, qdrant);
    }

    @Test
    void firstTick_takesFullForTheLatestSlot_thenAppliesRetention() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.OFF, 0, BackupFrequency.OFF));
        when(postgres.takeFull(any())).thenReturn(ARTIFACT);

        coordinator.runDueBackups();

        verify(recorder).start(BackupType.FULL, null, LAST_SUNDAY_2AM, null);
        verify(recorder).succeed(any(), eq(ARTIFACT));
        verify(postgres).applyRetention(4);
    }

    @Test
    void full_alreadyAnsweredThisSlot_isNotRepeated() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.OFF, 0, BackupFrequency.OFF));
        when(repository.findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(BackupType.FULL))
                .thenReturn(Optional.of(run(BackupType.FULL, LAST_SUNDAY_2AM, BackupStatus.FAILED)));

        coordinator.runDueBackups();

        verify(postgres, never()).takeFull(any());
    }

    @Test
    void full_failure_isRecordedAndSkipsRetention() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.OFF, 0, BackupFrequency.OFF));
        BackupException failure = new BackupException("pg_basebackup exited with 1");
        when(postgres.takeFull(any())).thenThrow(failure);

        coordinator.runDueBackups();

        verify(recorder).fail(any(), eq(failure));
        verify(postgres, never()).applyRetention(anyInt());
    }

    @Test
    void differential_runsAgainstTheLatestSuccessfulFull() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.DAILY, 0, BackupFrequency.OFF));
        BackupRun full = run(BackupType.FULL, LAST_SUNDAY_2AM, BackupStatus.SUCCEEDED);
        when(repository.findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(BackupType.FULL))
                .thenReturn(Optional.of(full));
        when(repository.findFirstByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(
                BackupType.FULL, BackupStatus.SUCCEEDED)).thenReturn(Optional.of(full));
        when(postgres.takeDifferential(any(), eq(full))).thenReturn(ARTIFACT);

        coordinator.runDueBackups();

        // Daily at 03:00, it is 09:00: today's slot.
        verify(recorder).start(BackupType.DIFFERENTIAL, null, TODAY_3AM, full.getId());
        verify(postgres).takeDifferential(any(), eq(full));
    }

    @Test
    void differential_withoutAFull_isSkippedAndNotRecorded() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.DAILY, 0, BackupFrequency.OFF));
        when(repository.findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(BackupType.FULL))
                .thenReturn(Optional.of(run(BackupType.FULL, LAST_SUNDAY_2AM, BackupStatus.FAILED)));

        coordinator.runDueBackups();

        verify(recorder, never()).start(eq(BackupType.DIFFERENTIAL), any(), any(), any());
    }

    @Test
    void wal_isDueOnceTheIntervalHasPassed() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.OFF, 15, BackupFrequency.OFF));
        answerFullSlot();
        when(repository.findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(BackupType.WAL))
                .thenReturn(Optional.of(startedAt(BackupType.WAL, NOW_UTC.minusMinutes(15))));
        when(wal.ship()).thenReturn(ARTIFACT);

        coordinator.runDueBackups();

        verify(wal).ship();
    }

    @Test
    void wal_withinTheInterval_waits() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.OFF, 15, BackupFrequency.OFF));
        answerFullSlot();
        when(repository.findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(BackupType.WAL))
                .thenReturn(Optional.of(startedAt(BackupType.WAL, NOW_UTC.minusMinutes(14))));

        coordinator.runDueBackups();

        verify(wal, never()).ship();
    }

    @Test
    void wal_withoutAnArchiveDir_isSkipped() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.OFF, 15, BackupFrequency.OFF));
        answerFullSlot();
        when(properties.getWalArchiveDir()).thenReturn("");

        coordinator.runDueBackups();

        verify(wal, never()).ship();
        verify(recorder, never()).start(eq(BackupType.WAL), any(), any(), any());
    }

    @Test
    void qdrant_snapshotsEveryCollection_andKeepsTheNewestN() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.OFF, 0, BackupFrequency.DAILY));
        answerFullSlot();
        when(qdrant.collections()).thenReturn(List.of("unisage_chunks", "faq"));
        when(qdrant.snapshot("unisage_chunks")).thenReturn(ARTIFACT);
        when(qdrant.snapshot("faq")).thenThrow(new BackupException("Qdrant could not snapshot faq"));

        coordinator.runDueBackups();

        verify(recorder).start(eq(BackupType.QDRANT_SNAPSHOT), eq("unisage_chunks"), any(), isNull());
        verify(recorder).start(eq(BackupType.QDRANT_SNAPSHOT), eq("faq"), any(), isNull());
        verify(qdrant).applyRetention("unisage_chunks", 7);
        verify(qdrant, never()).applyRetention(eq("faq"), anyInt());
    }

    @Test
    void qdrant_unreachable_recordsOneFailedRunForTheSlot() {
        when(settingsReader.read()).thenReturn(settings(true, BackupFrequency.OFF, 0, BackupFrequency.DAILY));
        answerFullSlot();
        when(qdrant.collections()).thenThrow(new BackupException("Qdrant could not list collections"));

        coordinator.runDueBackups();

        verify(recorder).start(eq(BackupType.QDRANT_SNAPSHOT), isNull(), any(), isNull());
        verify(recorder).fail(any(), any());
    }

    /** Full is never OFF; mark this week's Full as done so a test only sees the type it is about. */
    private void answerFullSlot() {
        when(repository.findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(BackupType.FULL))
                .thenReturn(Optional.of(run(BackupType.FULL, LAST_SUNDAY_2AM, BackupStatus.SUCCEEDED)));
    }

    private static BackupSettings settings(boolean enabled, BackupFrequency differential, int walMinutes,
            BackupFrequency qdrant) {
        return new BackupSettings(enabled, BackupFrequency.WEEKLY, LocalTime.of(2, 0),
                differential, LocalTime.of(3, 0), walMinutes, 4, qdrant, LocalTime.of(3, 30), 7);
    }

    private static BackupRun run(BackupType type, LocalDateTime slot, BackupStatus status) {
        return BackupRun.builder().id(UUID.randomUUID()).backupType(type).scheduledFor(slot)
                .startedAt(NOW_UTC.minusDays(2)).status(status).storagePrefix("postgres/full/x/").build();
    }

    private static BackupRun startedAt(BackupType type, LocalDateTime startedAt) {
        return BackupRun.builder().id(UUID.randomUUID()).backupType(type).scheduledFor(startedAt)
                .startedAt(startedAt).status(BackupStatus.SUCCEEDED).build();
    }
}
