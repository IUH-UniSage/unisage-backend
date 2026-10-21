package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.RestoreRun;
import com.unisage.backend.entity.enums.BackupStatus;

@Repository
public interface RestoreRunRepository extends JpaRepository<RestoreRun, UUID> {

    Page<RestoreRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    List<RestoreRun> findByStatus(BackupStatus status);
}
