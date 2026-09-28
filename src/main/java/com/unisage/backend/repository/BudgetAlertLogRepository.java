package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.unisage.backend.entity.BudgetAlertLog;
import com.unisage.backend.entity.enums.AlertChannel;

public interface BudgetAlertLogRepository extends JpaRepository<BudgetAlertLog, UUID> {

    Page<BudgetAlertLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<BudgetAlertLog> findByChannelAndDismissedAtIsNull(AlertChannel channel);
}
