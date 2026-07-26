package com.unisage.backend.dto.response;

import com.unisage.backend.entity.enums.UserStatus;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record UserResponse(
    UUID id,
    String email,
    String personalEmail,
    String firstName,
    String lastName,
    String avtUrl,
    String code,
    String roleName,
    UUID roleId,
    UUID accountId,
    String phone,
    String gender,
    UserStatus status,
    LocalDateTime lastLogin,
    LocalDateTime createdAt
) {}
