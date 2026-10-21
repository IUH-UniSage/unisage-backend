package com.unisage.backend.service.backup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.PostgresRestoreRequest;
import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.RestoreRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.entity.enums.RestoreType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BackupRunRepository;
import com.unisage.backend.repository.RestoreRunRepository;
import com.unisage.backend.service.backup.RestoreJobs.Phase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRestoreServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0);

    @TempDir
    Path restoreDir;

    private final BackupRunRepository backupRuns = mock(BackupRunRepository.class);
    private final RestoreRunRepository restoreRuns = mock(RestoreRunRepository.class);
    private final BackupCoordinator coordinator = mock(BackupCoordinator.class);
    private final BackupTaskRunner taskRunner = mock(BackupTaskRunner.class);
    private final BackupProperties properties = mock(BackupProperties.class);
    private final BackupRunRecorder recorder = mock(BackupRunRecorder.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<Flyway> flyway = mock(ObjectProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RestoreJobs jobs = new RestoreJobs(properties);

    private final PostgresRestoreService service = new PostgresRestoreService(jobs, backupRuns, restoreRuns,
            coordinator, mock(BackupStorage.class), taskRunner, properties, recorder, jdbcTemplate, objectMapper,
            flyway);

    // Full on the 4th, a Differential against it on the 11th, a newer Full on the 12th.
    private final BackupRun oldFull = backup(BackupType.FULL, null, LocalDateTime.of(2026, 10, 4, 2, 10));
    private final BackupRun differential = backup(BackupType.DIFFERENTIAL, oldFull.getId(),
            LocalDateTime.of(2026, 10, 11, 3, 5));
    private final BackupRun newFull = backup(BackupType.FULL, null, LocalDateTime.of(2026, 10, 12, 2, 10));

    @BeforeEach
    void setUp() throws Exception {
        when(properties.getRestoreDir()).thenReturn(restoreDir.toString());
        when(properties.getRestoreHandoffMinutes()).thenReturn(5L);
        Files.writeString(restoreDir.resolve(RestoreJobs.ENTRYPOINT_MARKER), "1");
        when(recorder.nowUtc()).thenReturn(NOW);
        when(coordinator.walArchivingConfigured()).thenReturn(true);
        when(backupRuns.findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.FULL,
                BackupStatus.SUCCEEDED)).thenReturn(List.of(newFull, oldFull));
        when(backupRuns.findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.DIFFERENTIAL,
                BackupStatus.SUCCEEDED)).thenReturn(List.of(differential));
        when(backupRuns.findById(any())).thenAnswer(call -> List.of(oldFull, differential, newFull).stream()
                .filter(run -> run.getId().equals(call.getArgument(0))).findFirst());
        when(restoreRuns.save(any())).thenAnswer(call -> {
            RestoreRun run = call.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID());
            }
            return run;
        });
    }

    @Test
    void plan_aDifferential_restoresItOnItsFull() {
        PostgresRestoreService.Plan plan = service.plan(new PostgresRestoreRequest(differential.getId(), null));

        assertThat(plan.full()).isSameAs(oldFull);
        assertThat(plan.differential()).isSameAs(differential);
        assertThat(plan.targetTime()).isNull();
    }

    @Test
    void plan_aTime_startsFromTheNewestBackupFinishedByThen() {
        PostgresRestoreService.Plan plan = service.plan(
                new PostgresRestoreRequest(null, LocalDateTime.of(2026, 10, 11, 18, 0)));

        assertThat(plan.restored()).isSameAs(differential);
        assertThat(plan.full()).isSameAs(oldFull);
        assertThat(plan.targetTime()).isEqualTo(LocalDateTime.of(2026, 10, 11, 18, 0));
    }

    @Test
    void plan_aDifferentialWhoseFullWasPurged_isNotRestorable() {
        when(backupRuns.findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.FULL,
                BackupStatus.SUCCEEDED)).thenReturn(List.of(newFull));

        assertThatThrownBy(() -> service.plan(new PostgresRestoreRequest(differential.getId(), null)))
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_RUN_NOT_RESTORABLE);
    }

    @Test
    void plan_rejectsBothOrNeitherChoice() {
        assertThatThrownBy(() -> service.plan(new PostgresRestoreRequest(null, null)))
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_RESTORE_REQUEST_INVALID);
        assertThatThrownBy(() -> service.plan(new PostgresRestoreRequest(newFull.getId(), NOW)))
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_RESTORE_REQUEST_INVALID);
    }

    @Test
    void plan_aTimeOutsideTheWindow_isRejected() {
        // Before the oldest kept backup finished.
        assertThatThrownBy(() -> service.plan(new PostgresRestoreRequest(null, LocalDateTime.of(2026, 10, 1, 0, 0))))
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_RESTORE_TARGET_INVALID);
        // In the future.
        assertThatThrownBy(() -> service.plan(new PostgresRestoreRequest(null, NOW.plusMinutes(1))))
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_RESTORE_TARGET_INVALID);
    }

    @Test
    void plan_aTimeWithoutWalArchiving_isRejected() {
        when(coordinator.walArchivingConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.plan(new PostgresRestoreRequest(null, LocalDateTime.of(2026, 10, 12, 8, 0))))
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_RESTORE_TARGET_INVALID);
    }

    @Test
    void start_withoutTheEntrypointMarker_isUnavailable() throws Exception {
        Files.delete(restoreDir.resolve(RestoreJobs.ENTRYPOINT_MARKER));

        assertThatThrownBy(() -> service.start(new PostgresRestoreRequest(newFull.getId(), null), null, "SA-001"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_RESTORE_UNAVAILABLE);
    }

    @Test
    void start_whileABackupRuns_isBusy() {
        when(taskRunner.isBusy()).thenReturn(true);

        assertThatThrownBy(() -> service.start(new PostgresRestoreRequest(newFull.getId(), null), null, "SA-001"))
                .extracting("errorCode").isEqualTo(ErrorCode.BACKUP_TASK_BUSY);
        verify(restoreRuns, never()).save(any());
    }

    @Test
    void start_recordsTheRunAndQueuesThePreparation() {
        when(taskRunner.submit(anyString(), any())).thenReturn(true);

        RestoreRun run = service.start(new PostgresRestoreRequest(differential.getId(), null), null, "SA-001");

        assertThat(run.getStatus()).isEqualTo(BackupStatus.RUNNING);
        assertThat(run.getSourceRunId()).isEqualTo(differential.getId());
        assertThat(jobs.status(run.getId()).orElseThrow().phase()).isEqualTo(Phase.PREPARING);
        assertThat(service.isRestoring()).isTrue();
    }

    @Test
    void finishCompletedJobs_writesTheHistoryBackFullsFirst_andMarksTheRunSucceeded() throws Exception {
        RestoreRun run = RestoreRun.builder().id(UUID.randomUUID()).restoreType(RestoreType.POSTGRES)
                .status(BackupStatus.RUNNING).startedAt(NOW).build();
        when(restoreRuns.findById(run.getId())).thenReturn(Optional.of(run));
        jobs.create(run.getId());
        jobs.writeCarryOver(run.getId(), objectMapper.writeValueAsString(
                new PostgresRestoreService.CarryOver(List.of(differential, oldFull), List.of(run))));
        jobs.markReady(run.getId());
        jobs.writeStatus(run.getId(), Phase.SUCCEEDED, "Khôi phục xong");
        List<Object> insertedIds = new ArrayList<>();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            insertedIds.add(call.getArgument(1));
            return 1;
        });

        service.finishCompletedJobs();

        assertThat(insertedIds).containsExactly(oldFull.getId(), differential.getId(), run.getId());
        assertThat(run.getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        assertThat(jobs.status(run.getId()).orElseThrow().finished()).isTrue();
    }

    @Test
    void finishCompletedJobs_aJobTheContainerNeverPickedUp_fails() throws Exception {
        UUID id = UUID.randomUUID();
        jobs.create(id);
        jobs.writeStatus(id, Phase.PREPARING, "Đang chờ container Postgres nhận bản sao lưu");
        jobs.markReady(id);
        Files.setLastModifiedTime(jobs.dir(id).resolve("status"),
                FileTime.from(Instant.now().minus(10, ChronoUnit.MINUTES)));

        service.finishCompletedJobs();

        assertThat(jobs.status(id).orElseThrow().phase()).isEqualTo(Phase.FAILED);
    }

    private static BackupRun backup(BackupType type, UUID base, LocalDateTime startedAt) {
        return BackupRun.builder().id(UUID.randomUUID()).backupType(type).status(BackupStatus.SUCCEEDED)
                .baseRunId(base).scheduledFor(startedAt).startedAt(startedAt).finishedAt(startedAt.plusMinutes(5))
                .storagePrefix("postgres/" + type.name().toLowerCase() + "/" + startedAt + "/").build();
    }
}
