package com.unisage.backend.dto.request;

import lombok.Builder;
import java.util.List;
import java.util.UUID;

@Builder
public record RoleRequest(
    String name,
    Boolean isSystemRole,
    String description,
    Boolean isActive,
    List<UUID> permissionIds,
    /** Null = the role uses the default usage limit plan. */
    UUID usageLimitPlanId
) {}
