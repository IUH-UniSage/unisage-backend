package com.unisage.backend.service.auditlog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.response.AuditLogResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;

public interface AuditLogService {

    PageResponse<List<AuditLogResponse>> search(
            ResourceType resourceType,
            AuditAction action,
            UUID actorId,
            String actorCode,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            Pageable pageable);

    AuditLogResponse getById(UUID id);
}
