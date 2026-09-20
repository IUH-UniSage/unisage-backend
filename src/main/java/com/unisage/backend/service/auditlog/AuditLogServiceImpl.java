package com.unisage.backend.service.auditlog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.response.AuditLogResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.AuditLog;
import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.AuditLogRepository;

import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public PageResponse<List<AuditLogResponse>> search(
            ResourceType resourceType,
            AuditAction action,
            UUID actorId,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            Pageable pageable) {
        Page<AuditLog> page = auditLogRepository.findAll(
                buildSpec(resourceType, action, actorId, fromDate, toDate), pageable);
        return PageResponse.fromPage(page, this::mapToResponse);
    }

    @Override
    public AuditLogResponse getById(UUID id) {
        AuditLog log = auditLogRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.AUDIT_LOG_NOT_FOUND));
        return mapToResponse(log);
    }

    private Specification<AuditLog> buildSpec(
            ResourceType resourceType, AuditAction action, UUID actorId,
            LocalDateTime fromDate, LocalDateTime toDate) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (resourceType != null) {
                predicates.add(cb.equal(root.get("resourceType"), resourceType));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AuditLogResponse mapToResponse(AuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .action(log.getAction())
                .resourceType(log.getResourceType())
                .resourceId(log.getResourceId())
                .actorId(log.getActorId())
                .actorName(log.getActorName())
                .actorCode(log.getActorCode())
                .details(log.getDetails())
                .ipAddress(log.getIpAddress())
                .userAgent(log.getUserAgent())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
