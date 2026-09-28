package com.unisage.backend.dto.request.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /internal/usage-logs} body - one business request's worth of usage, sent once by
 * unisage-agent's outbox worker. See changes/23-09-2026-Cost-Tracking-Budget-Management/plan.md
 * "Internal API & bảo mật" / "Data Model". {@code requestId} is the idempotency key - a retried
 * send with the same value is accepted as a no-op duplicate, never double-counted.
 */
public record UsageLogIngestRequest(
    @NotNull(message = "requestId không được để trống")
    UUID requestId,

    @NotNull(message = "purpose không được để trống")
    UsagePurpose purpose,

    UUID conversationId,

    UUID userMessageId,

    UUID assistantMessageId,

    UUID userId,

    String guestIp,

    @NotNull(message = "status không được để trống")
    UsageRequestStatus status,

    @NotNull(message = "startedAt không được để trống")
    OffsetDateTime startedAt,

    OffsetDateTime finishedAt,

    /** Never empty - a request with no provider call is settled by Python only, never sent here. */
    @NotEmpty(message = "lines không được để trống")
    List<@Valid UsageLineIngestRequest> lines
) {}
