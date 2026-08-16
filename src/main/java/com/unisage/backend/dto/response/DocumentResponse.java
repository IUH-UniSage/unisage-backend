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
    String fileType,
    DocStatus status,
    Boolean isPublic,
    Integer minAccessLevel,
    Integer version,
    UUID departmentId,
    String departmentPath,
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
