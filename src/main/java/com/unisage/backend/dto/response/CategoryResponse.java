package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

@Builder
public record CategoryResponse(
    UUID id,
    String name,
    String status,
    String description,
    Boolean isActive,
    LocalDateTime createdAt,
    String createdBy,
    String createdByName,
    LocalDateTime updatedAt,
    String updatedBy,
    String updatedByName
) {}
