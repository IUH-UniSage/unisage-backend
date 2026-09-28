package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.AlertChannel;
import com.unisage.backend.entity.enums.AlertStatus;
import com.unisage.backend.entity.enums.AlertType;

import lombok.Builder;

@Builder
public record BudgetAlertLogResponse(
    UUID id,
    AlertType alertType,
    UUID budgetId,
    LocalDate periodStart,
    Integer thresholdPercent,
    AlertChannel channel,
    BigDecimal spentUsd,
    BigDecimal limitUsd,
    AlertStatus status,
    Integer attemptCount,
    LocalDateTime lastAttemptAt,
    LocalDateTime nextAttemptAt,
    String errorMessage,
    LocalDateTime sentAt,
    LocalDateTime dismissedAt,
    String dismissedBy,
    LocalDateTime createdAt
) {}
