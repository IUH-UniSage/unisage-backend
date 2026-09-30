package com.unisage.backend.service.budget;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.response.BudgetAlertLogResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.BudgetAlertLog;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.AlertChannel;
import com.unisage.backend.entity.enums.AlertStatus;
import com.unisage.backend.entity.enums.AlertType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BudgetAlertLogRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BudgetAlertLogServiceImpl implements BudgetAlertLogService {

    private final BudgetAlertLogRepository budgetAlertLogRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;

    @Override
    public PageResponse<List<BudgetAlertLogResponse>> getAll(Pageable pageable, AlertType alertType,
            AlertChannel channel, AlertStatus status) {
        Page<BudgetAlertLog> page = budgetAlertLogRepository.search(alertType, channel, status, pageable);
        return PageResponse.fromPage(page, this::mapToResponse);
    }

    @Override
    public List<BudgetAlertLogResponse> getActive() {
        return budgetAlertLogRepository.findByChannelAndDismissedAtIsNull(AlertChannel.IN_APP).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void dismiss(UUID id) {
        BudgetAlertLog log = budgetAlertLogRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.BUDGET_ALERT_NOT_FOUND));
        if (log.getDismissedAt() != null) {
            return;
        }
        User currentUser = userRepository.findById(securityUtil.getCurrentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        log.setDismissedAt(LocalDateTime.now());
        log.setDismissedBy(currentUser);
        budgetAlertLogRepository.save(log);
    }

    private BudgetAlertLogResponse mapToResponse(BudgetAlertLog log) {
        return BudgetAlertLogResponse.builder()
                .id(log.getId())
                .alertType(log.getAlertType())
                .budgetId(log.getBudget() != null ? log.getBudget().getId() : null)
                .periodStart(log.getPeriodStart())
                .thresholdPercent(log.getThresholdPercent())
                .channel(log.getChannel())
                .spentUsd(log.getSpentUsd())
                .limitUsd(log.getLimitUsd())
                .status(log.getStatus())
                .attemptCount(log.getAttemptCount())
                .lastAttemptAt(log.getLastAttemptAt())
                .nextAttemptAt(log.getNextAttemptAt())
                .errorMessage(log.getErrorMessage())
                .sentAt(log.getSentAt())
                .dismissedAt(log.getDismissedAt())
                .dismissedBy(log.getDismissedBy() != null ? log.getDismissedBy().getFullName() : null)
                .createdAt(log.getCreatedAt())
                .build();
    }
}
