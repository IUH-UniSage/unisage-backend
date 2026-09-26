package com.unisage.backend.dto.request.internal;

import com.unisage.backend.entity.enums.VerificationResultType;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /internal/model-registry/verifications/{jobId}/result} body — plan.md "Internal API
 * contract" endpoint #5. {@code leaseToken} is the fencing token from the matching {@code claim}
 * response and is required regardless of {@code resultType}. {@code embeddingDimension}/
 * {@code embeddingFingerprint} are only meaningful (and only sent by the verifier) for an
 * EMBEDDING candidate's {@code OK} result — see plan.md "Embedding identity guard".
 */
public record InternalVerificationResultRequest(
    @NotNull(message = "leaseToken không được để trống")
    java.util.UUID leaseToken,

    @NotNull(message = "resultType không được để trống")
    VerificationResultType resultType,

    String errorCode,

    String message,

    Integer embeddingDimension,

    Float[] embeddingFingerprint
) {
}
