package com.unisage.backend.dto.response;

import com.unisage.backend.entity.enums.UserStatus;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder
public record UserDetailResponse(
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
    UUID accessLevelId,
    Integer accessLevel,
    String extraInfo,
    LocalDateTime lastLogin,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    String updatedBy,
    Integer totalQueries,
    List<String> topTopics,
    Set<String> permissions,
    List<DepartmentAccessResponse> departmentAccesses
) {}
