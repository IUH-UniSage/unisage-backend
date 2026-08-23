package com.unisage.backend.dto.request;

import lombok.Builder;

@Builder
public record PermissionRequest(
    String name,
    Boolean isActive
) {}
