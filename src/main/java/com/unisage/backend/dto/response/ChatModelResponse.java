package com.unisage.backend.dto.response;

import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record ChatModelResponse(
    UUID id,
    ChatModelPurpose modelPurpose,
    ChatModelStatus status,
    Integer revision,
    LocalDateTime verifiedAt,
    ChatModelSourceType sourceType,
    String llmProvider,
    String llmModelName,
    String displayName,
    String modelSourceRef,
    Boolean hasApiKey,
    String apiBaseUrl,
    Integer maxRpm,
    Integer priority,
    Integer errorCount,
    LocalDateTime lastErrorAt,
    String lastErrorCode,
    /** True when there's a candidate awaiting verification/promotion (an open QUEUED/RUNNING job). */
    Boolean hasPendingChange,
    ChatModelVerificationSummary latestVerification,
    Boolean isActive,
    LocalDateTime createdAt,
    String createdBy,
    String createdByName,
    LocalDateTime updatedAt,
    String updatedBy,
    String updatedByName
) {}
