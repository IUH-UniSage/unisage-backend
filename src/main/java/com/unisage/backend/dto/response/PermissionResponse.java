package com.unisage.backend.dto.response;

import lombok.Builder;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record PermissionResponse(
    UUID id,
    String name,
    Integer accessLevel,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    String updatedBy,
    Boolean isActive
) {}
