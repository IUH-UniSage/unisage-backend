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
    String createdByName,
    LocalDateTime updatedAt,
    String updatedBy,
    String updatedByName,
    Integer totalQueries,
    List<String> topTopics,
    Map<String, Integer> permissions,
    List<DepartmentAccessResponse> departmentAccesses
) {}
