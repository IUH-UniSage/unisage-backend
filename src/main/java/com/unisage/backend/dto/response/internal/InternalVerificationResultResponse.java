package com.unisage.backend.dto.response.internal;

/**
 * {@code POST /internal/model-registry/verifications/{jobId}/result} response — plan.md "Internal
 * API contract" endpoint #5. {@code duplicate = true} means this exact result was already accepted
 * by a previous submission (retried over the network) and nothing was written this time —
 * {@code applied} is always {@code false} together with {@code duplicate = true}. A lease that is
 * simply lost (wrong/expired token, job already superseded/cancelled) never reaches this response —
 * it is a 409 {@code VERIFICATION_LEASE_LOST} instead (see {@code GlobalExceptionHandler}).
 */
public record InternalVerificationResultResponse(boolean applied, boolean duplicate) {
}
