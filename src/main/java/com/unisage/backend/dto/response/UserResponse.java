package com.unisage.backend.dto.response;

import com.unisage.backend.entity.enums.UserStatus;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record UserResponse(
    UUID id,
    String email,
    String firstName,
    String lastName,
    String avatarUrl,
    String code,
    String roleName,
    UUID roleId,
    String phone,
    String gender,
    UserStatus status,
    LocalDateTime lastLogin,
    LocalDateTime createdAt,
    List<DepartmentAccessResponse> departmentAccesses
) {}
