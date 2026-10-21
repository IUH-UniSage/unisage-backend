package com.unisage.backend.dto.response.internal;

import java.time.LocalDateTime;
import java.util.UUID;

import com.unisage.backend.dto.response.internal.InternalVerificationClaimResponse.CandidateCredential;

/**
 * One claimed run — {@code POST /internal/embedding-reindex/claim} responds with a JSON array of
 * these. {@link CandidateCredential#apiKey()} is plaintext (no-store'd like every
 * {@code /internal/**} response), so {@code toString()} goes through the credential's redacted one.
 */
public record InternalEmbeddingReindexClaimResponse(
    UUID runId,
    UUID leaseToken,
    LocalDateTime leaseUntil,
    String sourceCollection,
    String targetCollection,
    /** The target model's current values — what the target collection's identity is built from. */
    CandidateCredential credential,
    int credentialRevision,
    Integer dimension,
    Float[] fingerprint,
    /** Copy the dense vectors and add {@code bm25} instead of re-embedding (same live model). */
    boolean rebuild
) {

    @Override
    public String toString() {
        return "InternalEmbeddingReindexClaimResponse[runId=" + runId
                + ", leaseToken=" + leaseToken
                + ", leaseUntil=" + leaseUntil
                + ", sourceCollection=" + sourceCollection
                + ", targetCollection=" + targetCollection
                + ", credential=" + credential
                + ", credentialRevision=" + credentialRevision
                + ", dimension=" + dimension
                + ", rebuild=" + rebuild + "]";
    }
}
