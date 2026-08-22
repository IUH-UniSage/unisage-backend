package com.unisage.backend.dto.request;

import jakarta.validation.constraints.Email;
import lombok.Builder;
import java.util.List;
import java.util.UUID;

@Builder
public record UpdateUserRequest(
    String firstName,
    String lastName,
    @Email
    String email,
    UUID roleId,
    String code,
    String phone,
    String gender,
    String extraInfo,
    UUID accessLevelId,

    List<DepartmentAccessItem> departmentAccesses
) {}
