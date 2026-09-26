package com.unisage.backend.dto.response.internal;

/**
 * {@code POST /internal/model-registry/credentials/{id}/health} response — plan.md "Internal API
 * contract" endpoint #3. {@code applied = false} means the report's {@code credentialRevision} was
 * stale (row already rotated past it) and was ignored entirely — never an error, just a no-op.
 */
public record CredentialHealthReportResponse(boolean applied) {
}
