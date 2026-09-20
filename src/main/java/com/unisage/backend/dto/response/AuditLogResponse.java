package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;

import lombok.Builder;

@Builder
public record AuditLogResponse(
    UUID id,
    AuditAction action,
    ResourceType resourceType,
    String resourceId,
    UUID actorId,
    String actorName,
    String actorCode,
    String details,
    String ipAddress,
    String userAgent,
    LocalDateTime createdAt
) {}
