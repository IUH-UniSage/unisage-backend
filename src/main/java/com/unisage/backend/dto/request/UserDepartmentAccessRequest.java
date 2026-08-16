package com.unisage.backend.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record UserDepartmentAccessRequest(
    @NotNull UUID roleId,
    @NotNull UUID departmentId,
    @NotNull Integer accessLevel
) {}
