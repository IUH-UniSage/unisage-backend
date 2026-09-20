package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.SystemConfigCategory;
import com.unisage.backend.entity.enums.ValueType;

import lombok.Builder;

@Builder
public record SystemConfigResponse(
    UUID id,
    String configKey,
    String value,
    ValueType valueType,
    SystemConfigCategory category,
    String label,
    String description,
    Boolean isEditable,
    Boolean isActive,
    LocalDateTime createdAt,
    String createdBy,
    String createdByName,
    LocalDateTime updatedAt,
    String updatedBy,
    String updatedByName
) {}
