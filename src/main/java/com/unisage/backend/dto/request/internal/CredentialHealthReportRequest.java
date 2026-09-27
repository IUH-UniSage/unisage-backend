package com.unisage.backend.dto.request.internal;

import java.time.OffsetDateTime;

import com.unisage.backend.entity.enums.CredentialHealthErrorType;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /internal/model-registry/credentials/{id}/health} body — plan.md "Internal API
 * contract" endpoint #3. Sent by {@code unisage-agent} after a provider call fails.
 *
 * <p>{@code credentialRevision} pins the report to the exact credential version the caller was
 * using — a stale value (rotated since) means the whole report is ignored (plan.md R2.6). {@code
 * snapshotVersion} is carried for observability/debugging only, not compared against anything
 * here. {@code errorCode}/{@code message} are provider-supplied free text and may be null.
 */
public record CredentialHealthReportRequest(
    @NotNull(message = "credentialRevision không được để trống")
    Integer credentialRevision,

    @NotNull(message = "snapshotVersion không được để trống")
    Long snapshotVersion,

    @NotNull(message = "errorType không được để trống")
    CredentialHealthErrorType errorType,

    String errorCode,

    String message,

    @NotNull(message = "occurredAt không được để trống")
    OffsetDateTime occurredAt
) {}
