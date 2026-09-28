package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;

import lombok.Builder;

@Builder
public record UsageLogListItemResponse(
    UUID id,
    UUID requestId,
    UsagePurpose purpose,
    UsageRequestStatus status,
    UUID userId,
    String guestIp,
    Integer totalInputTokens,
    Integer totalOutputTokens,
    BigDecimal totalCostUsd,
    BigDecimal estimatedUnpricedCostUsd,
    Integer latencyMs,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    /** True when any line has attempt >= 1 - a failover happened during this request. */
    boolean hasFailover
) {}
