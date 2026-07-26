package com.unisage.backend.dto.response;

import lombok.Builder;
import java.util.List;
import java.util.UUID;

@Builder
public record UserProfileResponse(
    UUID userId,
    String fullName,
    String avatarUrl,
    String role,
    String roleDescription,
    Boolean isSystemRole,
    List<PermissionInfo> permissions
) {}
