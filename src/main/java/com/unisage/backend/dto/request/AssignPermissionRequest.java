package com.unisage.backend.dto.request;

import lombok.Builder;
import java.util.UUID;

@Builder
public record AssignPermissionRequest(
    UUID roleId,
    UUID permissionId
) {}
