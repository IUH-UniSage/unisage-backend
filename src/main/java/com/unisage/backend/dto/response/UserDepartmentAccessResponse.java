package com.unisage.backend.dto.response;

import java.util.UUID;

import lombok.Builder;

@Builder
public record UserDepartmentAccessResponse(
    UUID roleId,
    String roleName,
    UUID departmentId,
    String departmentName,
    Integer accessLevel
) {}
