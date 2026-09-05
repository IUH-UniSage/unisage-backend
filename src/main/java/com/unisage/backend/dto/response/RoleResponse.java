package com.unisage.backend.dto.response;

import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record RoleResponse(
    UUID id,
    String name,
    String description,
    Boolean isSystemRole,
    List<PermissionInfo> permissions,
    LocalDateTime createdAt,
    String createdBy,
    String createdByName,
    LocalDateTime updatedAt,
    String updatedBy,
    String updatedByName,
    Boolean isActive
) {}
