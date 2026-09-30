package com.unisage.backend.dto.request.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsageRequestStatus;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One line of {@code POST /internal/usage-logs}'s {@code lines} array - one provider call attempt.
 * See changes/23-09-2026-Cost-Tracking-Budget-Management/plan.md "Data Model".
 */
public record UsageLineIngestRequest(
    @NotNull(message = "seq không được để trống")
    @Min(value = 0, message = "seq phải >= 0")
    Integer seq,

    @NotNull(message = "nodeName không được để trống")
    String nodeName,

    @NotNull(message = "attempt không được để trống")
    @Min(value = 0, message = "attempt phải >= 0")
    Integer attempt,

    UUID chatModelId,

    String provider,

    String modelName,

    ChatModelSourceType sourceType,

    @NotNull(message = "inputTokens không được để trống")
    @Min(value = 0, message = "inputTokens phải >= 0")
    Integer inputTokens,

    @NotNull(message = "outputTokens không được để trống")
    @Min(value = 0, message = "outputTokens phải >= 0")
    Integer outputTokens,

    @NotNull(message = "cachedTokens không được để trống")
    @Min(value = 0, message = "cachedTokens phải >= 0")
    Integer cachedTokens,

    /** Null unless costStatus = PRICED - validated in the service, not by a JSR-380 annotation (cross-field rule). */
    BigDecimal costUsd,

    @NotNull(message = "estimatedCostUsd không được để trống")
    BigDecimal estimatedCostUsd,

    @NotNull(message = "costStatus không được để trống")
    UsageCostStatus costStatus,

    Integer latencyMs,

    @NotNull(message = "status không được để trống")
    UsageRequestStatus status,

    String errorCode,

    @NotNull(message = "occurredAt không được để trống")
    OffsetDateTime occurredAt
) {}
