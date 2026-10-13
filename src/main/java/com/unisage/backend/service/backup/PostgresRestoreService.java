package com.unisage.backend.service.backup;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.unisage.backend.service.backup.RestoreJobs.JobStatus;
import com.unisage.backend.service.backup.RestoreJobs.Phase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Restores the whole Postgres cluster from the backups of ADR-0008, started from the admin page
 * (ADR-0010). The backend cannot stop the database it runs on, so it only prepares the job: it
 * ships the newest WAL, takes a safety Full of the current state, downloads the chosen backup into
 * the restore folder and hands it to the unisage-db container ({@link RestoreJobs}), which swaps
 * the data directory and replays WAL. Once Postgres is back, {@link #finishCompletedJobs()}
 * writes the backup history back: the restored database holds the history as it was at the
 * restore point, and the backups taken since then are still in MinIO.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostgresRestoreService {

    /** recovery_target_time, always in UTC; unisage-entrypoint.sh only accepts this shape. */
    static final DateTimeFormatter TARGET_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'+00'");
    static final String IMMEDIATE = "immediate";

    private final RestoreJobs jobs;
    private final BackupRunRepository backupRunRepository;
    private final RestoreRunRepository restoreRunRepository;
    private final BackupCoordinator coordinator;
    private final BackupStorage storage;
    private final BackupTaskRunner taskRunner;
    private final BackupProperties properties;
    private final BackupRunRecorder recorder;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<Flyway> flyway;

    /** The backups a restore starts from; {@code differential} may be null. */
    record Plan(BackupRun full, BackupRun differential, LocalDateTime targetTime) {
        BackupRun restored() {
            return differential != null ? differential : full;
        }
    }

    /** What the database loses on restore and gets back afterwards, see {@link #writeBack}. */
    record CarryOver(List<BackupRun> backupRuns, List<RestoreRun> restoreRuns) {
    }

    public RestoreRun start(PostgresRestoreRequest request, UUID userId, String userCode) {
        if (!jobs.isAvailable()) {
            throw new AppException(ErrorCode.BACKUP_RESTORE_UNAVAILABLE);
        }
        if (taskRunner.isBusy() || isRestoring()) {
            throw new AppException(ErrorCode.BACKUP_TASK_BUSY);
        }
        Plan plan = plan(request);
        RestoreRun run = restoreRunRepository.save(RestoreRun.builder()
                .restoreType(RestoreType.POSTGRES)
                .status(BackupStatus.RUNNING)
                .sourceRunId(plan.restored().getId())
                .targetTime(plan.targetTime())
                .requestedBy(userId)
                .requestedByCode(userCode)
                .startedAt(recorder.nowUtc())
                .build());
        UUID id = run.getId();
        jobs.create(id);
        jobs.writeStatus(id, Phase.PREPARING, "Đang chờ bắt đầu");
        if (!taskRunner.submit("Postgres restore " + id, () -> prepare(id, plan))) {
            fail(id, "Another backup task started first");
            throw new AppException(ErrorCode.BACKUP_TASK_BUSY);
        }
        log.warn("Postgres restore {} requested by {}: {} {}", id, userCode,
                plan.restored().getStoragePrefix(), plan.targetTime() == null ? "as taken" : "to " + plan.targetTime());
        return run;
    }

    Plan plan(PostgresRestoreRequest request) {
        if ((request.backupRunId() == null) == (request.targetTime() == null)) {
            throw new AppException(ErrorCode.BACKUP_RESTORE_REQUEST_INVALID);
        }
        List<BackupRun> points = restorePoints();
        if (request.backupRunId() != null) {
            BackupRun chosen = backupRunRepository.findById(request.backupRunId())
                    .orElseThrow(() -> new AppException(ErrorCode.BACKUP_RUN_NOT_FOUND));
            if (points.stream().noneMatch(point -> point.getId().equals(chosen.getId()))) {
                throw new AppException(ErrorCode.BACKUP_RUN_NOT_RESTORABLE);
            }
            return planFor(chosen, null, points);
        }
        LocalDateTime target = request.targetTime();
        if (!coordinator.walArchivingConfigured() || target.isAfter(recorder.nowUtc())) {
            throw new AppException(ErrorCode.BACKUP_RESTORE_TARGET_INVALID);
        }
        // The newest backup finished by the target; WAL replays it forward to the target.
        BackupRun base = points.stream()
                .filter(point -> !point.getFinishedAt().isAfter(target))
                .max(Comparator.comparing(BackupRun::getFinishedAt))
                .orElseThrow(() -> new AppException(ErrorCode.BACKUP_RESTORE_TARGET_INVALID));
        return planFor(base, target, points);
    }

    private static Plan planFor(BackupRun chosen, LocalDateTime target, List<BackupRun> points) {
        if (chosen.getBackupType() == BackupType.FULL) {
            return new Plan(chosen, null, target);
        }
        BackupRun full = points.stream()
                .filter(point -> point.getId().equals(chosen.getBaseRunId()))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.BACKUP_RUN_NOT_RESTORABLE));
        return new Plan(full, chosen, target);
    }

    /**
     * Successful Fulls and Differentials still in storage, newest first. A Differential counts
     * only while its Full is kept too.
     */
    public List<BackupRun> restorePoints() {
        List<BackupRun> fulls = backupRunRepository
                .findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.FULL, BackupStatus.SUCCEEDED);
        Set<UUID> fullIds = fulls.stream().map(BackupRun::getId).collect(Collectors.toSet());
        List<BackupRun> points = new ArrayList<>(fulls);
        backupRunRepository
                .findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(BackupType.DIFFERENTIAL, BackupStatus.SUCCEEDED)
                .stream()
                .filter(differential -> fullIds.contains(differential.getBaseRunId()))
                .forEach(points::add);
        points.sort(Comparator.comparing(BackupRun::getStartedAt).reversed());
        return points;
    }

    /** Earliest moment a point-in-time restore can reach: the end of the oldest kept backup. */
    public Optional<LocalDateTime> pointInTimeFrom() {
        return restorePoints().stream().map(BackupRun::getFinishedAt).min(Comparator.naturalOrder());
    }

    public boolean isRestoring() {
        return jobs.isConfigured() && jobs.active().isPresent();
    }

    /** Progress of the newest Postgres restore; read from files, so it works while Postgres is down. */
    public Optional<JobStatus> latestStatus() {
        if (!jobs.isConfigured()) {
            return Optional.empty();
        }
        Optional<JobStatus> latest = jobs.all().stream().findFirst();
        if (latest.isPresent() && !latest.get().phase().isActive() && !latest.get().finished()) {
            // Postgres is up again: write the outcome now instead of on the next tick.
            try {
                finishCompletedJobs();
            } catch (RuntimeException e) {
                log.debug("Restore outcome not written yet: {}", e.getMessage());
            }
        }
        return latest;
    }

    private void prepare(UUID id, Plan plan) {
        try {
            if (coordinator.walArchivingConfigured()) {
                progress(id, "Đang đẩy nhật ký giao dịch mới nhất lên MinIO");
                BackupRun wal = coordinator.shipWalNow();
                if (wal.getStatus() != BackupStatus.SUCCEEDED && plan.targetTime() != null) {
                    throw new BackupException("could not ship the latest WAL: " + wal.getErrorMessage());
                }
            }

            progress(id, "Đang tạo bản Full an toàn của dữ liệu hiện tại");
            BackupRun safety = coordinator.runFullNow();
            if (safety.getStatus() != BackupStatus.SUCCEEDED) {
                throw new BackupException("could not take the safety Full backup: " + safety.getErrorMessage());
            }
            restoreRunRepository.findById(id).ifPresent(run -> {
                run.setSafetyRunId(safety.getId());
                restoreRunRepository.save(run);
            });

            progress(id, "Đang tải bản sao lưu từ MinIO");
            Path dir = jobs.dir(id);
            storage.downloadPrefix(plan.full().getStoragePrefix(), dir.resolve("full"));
            if (plan.differential() != null) {
                storage.downloadPrefix(plan.differential().getStoragePrefix(), dir.resolve("diff"));
            }
            if (plan.targetTime() != null) {
                downloadWal(plan.restored(), dir.resolve("wal"));
            }
            jobs.writeTarget(id, plan.targetTime() == null ? IMMEDIATE : TARGET_TIME.format(plan.targetTime()));
            jobs.writeCarryOver(id, carryOver());

            progress(id, "Đang chờ container Postgres nhận bản sao lưu");
            jobs.markReady(id);
        } catch (RuntimeException e) {
            log.error("Postgres restore {} failed before the database was touched", id, e);
            jobs.deleteDownloads(id);
            fail(id, BackupRunRecorder.describe(e));
        }
    }

    /**
     * WAL archived after {@code base} started: every segment recovery replays past the backup's
     * own copy. Timeline history files are always needed to follow an earlier restore's branch.
     */
    private void downloadWal(BackupRun base, Path dir) {
        Instant from = base.getStartedAt().toInstant(ZoneOffset.UTC);
        for (BackupStorage.StoredObject object : storage.list(PostgresBackupService.WAL_PREFIX)) {
            if (object.key().endsWith(".history") || !object.lastModified().isBefore(from)) {
                storage.download(object.key(),
                        dir.resolve(object.key().substring(PostgresBackupService.WAL_PREFIX.length())));
            }
        }
    }

    private String carryOver() {
        try {
            return objectMapper.writeValueAsString(
                    new CarryOver(backupRunRepository.findAll(), restoreRunRepository.findAll()));
        } catch (JsonProcessingException e) {
            throw new BackupException("cannot save the backup history for the restore", e);
        }
    }

    /**
     * Writes the outcome of every restore the database container has finished. Runs on the
     * backup tick and when the page polls; does nothing for jobs still in progress.
     */
    public synchronized void finishCompletedJobs() {
        if (!jobs.isConfigured()) {
            return;
        }
        for (JobStatus job : jobs.all()) {
            if (job.finished()) {
                continue;
            }
            if (job.phase() == Phase.PREPARING && job.ready() && handoffTimedOut(job)) {
                // Nobody picked it up: the container does not run unisage-entrypoint.sh.
                jobs.writeStatus(job.id(), Phase.FAILED,
                        "Container Postgres không nhận yêu cầu khôi phục. Dữ liệu chưa bị thay đổi.");
                continue;
            }
            if (job.phase().isActive()) {
                continue;
            }
            try {
                finish(job);
            } catch (RuntimeException e) {
                // Retried on the next tick; one bad job must not hold up the others.
                log.warn("Could not record the outcome of Postgres restore {} yet: {}", job.id(), e.getMessage());
            }
        }
    }

    private void finish(JobStatus job) {
        if (job.ready() && jobs.hasCarryOver(job.id())) {
            flyway.ifAvailable(Flyway::migrate);
            writeBack(job.id());
        }
        boolean succeeded = job.phase() == Phase.SUCCEEDED;
        restoreRunRepository.findById(job.id()).ifPresent(run -> finishRun(run,
                succeeded ? BackupStatus.SUCCEEDED : BackupStatus.FAILED, succeeded ? null : job.message()));
        jobs.markFinished(job.id());
        log.info("Postgres restore {} {}", job.id(), succeeded ? "succeeded" : "failed: " + job.message());
    }

    private boolean handoffTimedOut(JobStatus job) {
        return job.updatedAt().plus(Duration.ofMinutes(properties.getRestoreHandoffMinutes())).isBefore(Instant.now());
    }

    /**
     * Puts back the history saved before the restore. The saved rows win on conflict: they say
     * what is really in MinIO now (a backup the restored copy still thinks is RUNNING, or one
     * purged since).
     */
    void writeBack(UUID id) {
        CarryOver carryOver;
        try {
            carryOver = objectMapper.readValue(jobs.readCarryOver(id), CarryOver.class);
        } catch (JsonProcessingException e) {
            throw new BackupException("cannot read the history saved for restore " + id, e);
        }
        // A Differential references its Full, so Fulls (no base) go first.
        List<BackupRun> backups = new ArrayList<>(carryOver.backupRuns());
        backups.sort(Comparator.comparing(run -> run.getBaseRunId() != null));
        for (BackupRun run : backups) {
            jdbcTemplate.update("""
                    INSERT INTO backup_runs (id, backup_type, status, target, scheduled_for, started_at,
                        finished_at, base_run_id, storage_prefix, size_bytes, file_count, checksum,
                        error_message, purged_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, finished_at = EXCLUDED.finished_at,
                        storage_prefix = EXCLUDED.storage_prefix, size_bytes = EXCLUDED.size_bytes,
                        file_count = EXCLUDED.file_count, checksum = EXCLUDED.checksum,
                        error_message = EXCLUDED.error_message, purged_at = EXCLUDED.purged_at
                    """,
                    run.getId(), run.getBackupType().name(), run.getStatus().name(), run.getTarget(),
                    run.getScheduledFor(), run.getStartedAt(), run.getFinishedAt(), run.getBaseRunId(),
                    run.getStoragePrefix(), run.getSizeBytes(), run.getFileCount(), run.getChecksum(),
                    run.getErrorMessage(), run.getPurgedAt());
        }
        for (RestoreRun run : carryOver.restoreRuns()) {
            jdbcTemplate.update("""
                    INSERT INTO restore_runs (id, restore_type, status, source_run_id, target, target_time,
                        safety_run_id, requested_by, requested_by_code, started_at, finished_at, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, safety_run_id = EXCLUDED.safety_run_id,
                        finished_at = EXCLUDED.finished_at, error_message = EXCLUDED.error_message
                    """,
                    run.getId(), run.getRestoreType().name(), run.getStatus().name(), run.getSourceRunId(),
                    run.getTarget(), run.getTargetTime(), run.getSafetyRunId(), run.getRequestedBy(),
                    run.getRequestedByCode(), run.getStartedAt(), run.getFinishedAt(), run.getErrorMessage());
        }
    }

    /** A restart mid-way leaves rows RUNNING and jobs PREPARING that nothing will finish. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRestores() {
        if (jobs.isConfigured()) {
            for (JobStatus job : jobs.all()) {
                if (job.phase() == Phase.PREPARING && !job.ready()) {
                    jobs.deleteDownloads(job.id());
                    jobs.writeStatus(job.id(), Phase.FAILED, "Bị gián đoạn vì backend khởi động lại");
                }
            }
        }
        Set<UUID> pending = jobs.isConfigured()
                ? jobs.all().stream().filter(job -> !job.finished()).map(JobStatus::id).collect(Collectors.toSet())
                : Set.of();
        for (RestoreRun run : restoreRunRepository.findByStatus(BackupStatus.RUNNING)) {
            if (!pending.contains(run.getId())) {
                finishRun(run, BackupStatus.FAILED, "Interrupted by an application restart");
            }
        }
        finishCompletedJobs();
    }

    private void progress(UUID id, String message) {
        jobs.writeStatus(id, Phase.PREPARING, message);
    }

    private void fail(UUID id, String message) {
        jobs.writeStatus(id, Phase.FAILED, message);
        restoreRunRepository.findById(id).ifPresent(run -> finishRun(run, BackupStatus.FAILED, message));
        jobs.markFinished(id);
    }

    private void finishRun(RestoreRun run, BackupStatus status, String error) {
        run.setStatus(status);
        run.setFinishedAt(recorder.nowUtc());
        run.setErrorMessage(error);
        restoreRunRepository.save(run);
    }
}
