package com.unisage.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;

@Repository
public interface BackupRunRepository extends JpaRepository<BackupRun, UUID> {

    /** The last run of a type whatever its outcome: a failed slot is not retried until the next one. */
    Optional<BackupRun> findFirstByBackupTypeOrderByScheduledForDescStartedAtDesc(BackupType backupType);

    Optional<BackupRun> findFirstByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(
            BackupType backupType, BackupStatus status);

    List<BackupRun> findByBackupTypeAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(
            BackupType backupType, BackupStatus status);

    List<BackupRun> findByBackupTypeAndTargetAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(
            BackupType backupType, String target, BackupStatus status);

    List<BackupRun> findByBaseRunIdAndPurgedAtIsNull(UUID baseRunId);

    List<BackupRun> findByStatus(BackupStatus status);
}
