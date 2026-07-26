package com.unisage.backend.dto.response;

import lombok.Builder;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record AccountResponse(
    UUID id,
    String email,
    String code,
    Boolean isActive,
    LocalDateTime lastLogin,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    String updatedBy
) {}
