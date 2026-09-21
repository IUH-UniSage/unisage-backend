package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

@Builder
public record UsageLimitPlanResponse(
    UUID id,
    String name,
    Long dailyTokenLimit,
    Long weeklyTokenLimit,
    Boolean isDefault,
    Boolean isActive,
    LocalDateTime createdAt,
    String createdBy,
    String createdByName,
    LocalDateTime updatedAt,
    String updatedBy,
    String updatedByName
) {}
