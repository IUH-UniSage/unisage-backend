package com.unisage.backend.controller.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.internal.UsageLogIngestRequest;
import com.unisage.backend.dto.response.internal.UsageLogIngestResponse;
import com.unisage.backend.service.usagelog.RequestUsageLogService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Cost Tracking's internal namespace - plan.md "Internal API & bảo mật". Lives directly under
 * {@code /internal} (not nested under {@code /internal/model-registry}, which is Dynamic Model
 * Registry's own namespace) but shares the exact same security stack: {@code InternalSecretFilter}
 * + {@code InternalCallerCidrFilter} + {@code InternalResponseHeadersFilter} all match on
 * {@code /internal/**} generically, and {@code DynamicAuthorizationManager} grants purely on the
 * {@code TRUSTED_INTERNAL_CALLER_ATTRIBUTE} request attribute for any path under that prefix - no
 * endpoint-specific wiring was needed to add this controller.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalUsageLogController {

    private final RequestUsageLogService requestUsageLogService;

    /** unisage-agent's outbox worker calls this once per business request that made >= 1 provider call. */
    @PostMapping("/usage-logs")
    public UsageLogIngestResponse ingest(@Valid @RequestBody UsageLogIngestRequest request) {
        return requestUsageLogService.ingest(request);
    }
}
