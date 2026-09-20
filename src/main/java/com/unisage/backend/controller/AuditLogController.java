package com.unisage.backend.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.AuditLogResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;
import com.unisage.backend.service.auditlog.AuditLogService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<AuditLogResponse>>>> getAll(
            @RequestParam(required = false) ResourceType resourceType,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                auditLogService.search(resourceType, action, actorId, fromDate, toDate, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AuditLogResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(auditLogService.getById(id)));
    }
}
