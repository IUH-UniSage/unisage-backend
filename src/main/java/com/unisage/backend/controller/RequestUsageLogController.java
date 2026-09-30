package com.unisage.backend.controller;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.UsageLogDetailResponse;
import com.unisage.backend.dto.response.UsageLogListItemResponse;
import com.unisage.backend.dto.response.UsageLogSummaryResponse;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;
import com.unisage.backend.service.usagelog.RequestUsageLogService;
import com.unisage.backend.service.usagelog.UsageLogSearchFilter;

import lombok.RequiredArgsConstructor;

/** SA-facing cost dashboard and usage history endpoints. */
@RestController
@RequestMapping("/usage-logs")
@RequiredArgsConstructor
public class RequestUsageLogController {

    private final RequestUsageLogService requestUsageLogService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<UsageLogSummaryResponse>> summary(
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam String groupBy,
            @RequestParam(required = false) UsagePurpose purpose,
            @RequestParam(required = false) String provider) {
        return ResponseEntity.ok(ApiResponse.success(
                requestUsageLogService.summary(toUtc(from), toUtc(to), groupBy, purpose, provider)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<UsageLogListItemResponse>>>> search(
            @RequestParam(required = false) UsagePurpose purpose,
            @RequestParam(required = false) UsageRequestStatus status,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String userOrIp,
            Pageable pageable) {
        var filter = new UsageLogSearchFilter(purpose, status, toUtc(from), toUtc(to), provider, model, userOrIp);
        return ResponseEntity.ok(ApiResponse.success(requestUsageLogService.search(filter, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UsageLogDetailResponse>> getDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(requestUsageLogService.getDetail(id)));
    }

    private static java.time.LocalDateTime toUtc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
