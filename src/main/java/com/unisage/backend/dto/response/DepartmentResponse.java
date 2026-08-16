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
    List<DepartmentResponse> children,
    String createdBy,
    LocalDateTime createdAt,
    String updatedBy,
    LocalDateTime updatedAt
) {}
