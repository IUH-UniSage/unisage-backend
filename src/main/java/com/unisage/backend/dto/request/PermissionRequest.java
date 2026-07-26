package com.unisage.backend.dto.request;

import lombok.Builder;

@Builder
public record PermissionRequest(
    String name,
    Integer accessLevel,
    Boolean isActive
) {}
