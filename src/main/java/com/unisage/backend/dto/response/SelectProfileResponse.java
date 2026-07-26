package com.unisage.backend.dto.response;

import lombok.Builder;
import java.util.List;

@Builder
public record SelectProfileResponse(
    String accessToken,
    String refreshToken,
    long refreshTokenExpirationMs,
    String email,
    String fullName,
    String role,
    String code,
    String avatarUrl,
    Boolean isSystemRole,
    List<PermissionInfo> permissions
) {}
