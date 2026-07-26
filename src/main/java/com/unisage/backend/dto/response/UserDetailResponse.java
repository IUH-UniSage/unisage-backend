package com.unisage.backend.dto.response;

import com.unisage.backend.entity.enums.UserStatus;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder
public record UserDetailResponse(
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
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    String updatedBy,
    String address,
    String department,
    Integer totalQueries,
    List<String> topTopics,
    Map<String, Integer> permissions
) {}
