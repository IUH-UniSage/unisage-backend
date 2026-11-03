package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

/** {@code POST /shares} (UNISAGE-107 §5.2). */
@Builder
public record ShareResponse(
    UUID id,
    String token,
    String title,
    int messageCount,
    LocalDateTime createdAt
) {}
