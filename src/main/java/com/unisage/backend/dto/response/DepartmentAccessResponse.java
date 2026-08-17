package com.unisage.backend.dto.response;

import java.util.UUID;

import lombok.Builder;

@Builder
public record DepartmentAccessResponse(
    UUID departmentId,
    String departmentName,
    Integer accessLevel
) {}
