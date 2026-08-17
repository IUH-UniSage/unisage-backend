package com.unisage.backend.dto.response;

import lombok.Builder;
import java.util.UUID;

@Builder
public record PermissionInfo(
    UUID id,
    String name,
    Integer accessLevel
) {}