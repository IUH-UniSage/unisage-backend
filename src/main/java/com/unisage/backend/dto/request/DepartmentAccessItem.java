package com.unisage.backend.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record DepartmentAccessItem(
    @NotNull UUID departmentId,
    @NotNull UUID accessLevelId
) {}
