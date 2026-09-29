package com.unisage.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;

import lombok.Builder;

/** {@code query}/{@code answer}/{@code citations} are null (not an error) when the underlying
 * message was purged (guest cleanup nulls the FK via ON DELETE SET NULL) - plan.md "Request
 * detail join Message có sẵn". */
@Builder
public record UsageLogDetailResponse(
    UUID id,
    UUID requestId,
    UsagePurpose purpose,
    UsageRequestStatus status,
    UUID userId,
    String userEmail,
    String guestIp,
    Integer totalInputTokens,
    Integer totalOutputTokens,
    Integer totalCachedTokens,
    BigDecimal totalCostUsd,
    BigDecimal estimatedUnpricedCostUsd,
    Integer unpricedLineCount,
    Integer lineCount,
    Integer latencyMs,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    String query,
    String answer,
    List<Map<String, Object>> citations,
    List<UsageLineResponse> lines
) {}
