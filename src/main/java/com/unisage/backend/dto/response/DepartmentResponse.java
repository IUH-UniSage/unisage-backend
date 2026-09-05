package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import lombok.Builder;

@Builder
public record DepartmentResponse(
    UUID id,
    String name,
    String description,
    UUID parentId,
    Boolean isActive,
    LocalDateTime deletedAt,
    List<DepartmentNodeResponse> children,
    String createdBy,
    String createdByName,
    LocalDateTime createdAt,
    String updatedBy,
    String updatedByName,
    LocalDateTime updatedAt
) {}
