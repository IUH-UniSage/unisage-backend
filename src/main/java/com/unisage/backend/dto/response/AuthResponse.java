package com.unisage.backend.dto.response;

import lombok.Builder;
import java.util.List;

@Builder
public record AuthResponse(
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
