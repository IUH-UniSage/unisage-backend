package com.unisage.backend.dto.response;

import lombok.Builder;
import java.util.List;
import java.util.UUID;

@Builder
public record AuthResponse(
    UUID userId,
    String accessToken,
    String refreshToken,
    long refreshTokenExpirationMs,
    String email,
    String code,
    String fullName,
    String avatarUrl,
    String role,
    Boolean isSystemRole,
    List<PermissionInfo> permissions
) {}
