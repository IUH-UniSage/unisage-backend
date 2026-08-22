package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.DocStatus;

import lombok.Builder;

@Builder
public record DocumentResponse(
    UUID id,
    String title,
    String sourceUrl,
    String fileUrl,
    String fileType,
    DocStatus status,
    Boolean isPublic,
    UUID minAccessLevelId,
    Integer minAccessLevel,
    Integer version,
    UUID departmentId,
    String departmentName,
    UUID categoryId,
    String categoryName,
    UUID ingestedByUserId,
    Boolean isActive,
    LocalDateTime deletedAt,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    String updatedBy
) {}
