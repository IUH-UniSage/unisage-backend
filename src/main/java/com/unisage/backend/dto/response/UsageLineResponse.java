package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsageRequestStatus;

import lombok.Builder;

@Builder
public record UsageLineResponse(
    Integer seq,
    String nodeName,
    Integer attempt,
    UUID chatModelId,
    String provider,
    String modelName,
    ChatModelSourceType sourceType,
    Integer inputTokens,
    Integer outputTokens,
    Integer cachedTokens,
    BigDecimal costUsd,
    BigDecimal estimatedCostUsd,
    UsageCostStatus costStatus,
    Integer latencyMs,
    UsageRequestStatus status,
    String errorCode,
    LocalDateTime occurredAt
) {}
