package com.unisage.backend.dto.response.internal;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;

/**
 * One claimed job — {@code POST /internal/model-registry/verifications/claim} responds with a JSON
 * array of these (plan.md "Internal API contract" endpoint #4). {@link CandidateCredential#apiKey()}
 * is plaintext, same caveat as the snapshot endpoint's {@code CredentialEntry} — this response is
 * covered by {@code InternalResponseHeadersFilter}'s {@code no-store}, and must never be logged with
 * a naive {@code toString()}, hence the redacted override below.
 */
public record InternalVerificationClaimResponse(
    UUID jobId,
    UUID leaseToken,
    int attempt,
    LocalDateTime leaseUntil,
    CandidateCredential credential
) {

    @Override
    public String toString() {
        return "InternalVerificationClaimResponse[jobId=" + jobId
                + ", leaseToken=" + leaseToken
                + ", attempt=" + attempt
                + ", leaseUntil=" + leaseUntil
                + ", credential=" + credential + "]";
    }

    /** The candidate values under verification — never the row's currently-active values. */
    public record CandidateCredential(
        UUID chatModelId,
        ChatModelPurpose modelPurpose,
        ChatModelSourceType sourceType,
        String provider,
        String modelName,
        String modelSourceRef,
        String apiBaseUrl,
        String apiKey,
        Integer maxRpm
    ) {
        @Override
        public String toString() {
            return "CandidateCredential[chatModelId=" + chatModelId
                    + ", modelPurpose=" + modelPurpose
                    + ", sourceType=" + sourceType
                    + ", provider=" + provider
                    + ", modelName=" + modelName
                    + ", modelSourceRef=" + modelSourceRef
                    + ", apiBaseUrl=" + apiBaseUrl
                    + ", apiKey=REDACTED, maxRpm=" + maxRpm + "]";
        }
    }
}
