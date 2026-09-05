package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

@Builder
public record AccessLevelResponse(
    UUID id,
    Integer level,
    String description,
    Boolean isActive,
    LocalDateTime createdAt,
    String createdBy,
    String createdByName,
    LocalDateTime updatedAt,
    String updatedBy,
    String updatedByName
) {}
