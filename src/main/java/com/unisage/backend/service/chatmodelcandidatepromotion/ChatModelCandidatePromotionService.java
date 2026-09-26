package com.unisage.backend.service.chatmodelcandidatepromotion;

import com.unisage.backend.entity.ChatModelVerification;

/**
 * Decides what a successfully-verified candidate does to its row — plan.md "Credential rotation"
 * (the CAS promote) and "Embedding identity guard" (the EMBEDDING-specific branch that can end a
 * job in {@code REINDEX_REQUIRED} instead of promoting).
 *
 * <p>This is deliberately just a service method with no HTTP endpoint: Task 6 (claim/result
 * lifecycle — not built yet on this branch) is what will actually receive a verifier's "OK, here's
 * the measured dimension/fingerprint" result over HTTP, apply its own fencing-token/lease checks,
 * and then call this method inside the same transaction. Task 3's job is only to get the
 * EMBEDDING-vs-CAS decision itself right so Task 6 has something correct to call.
 */
public interface ChatModelCandidatePromotionService {

    enum Outcome {
        /** Candidate applied to the row; job is SUCCEEDED. */
        PROMOTED,
        /** CAS lost the race (row's generation/revision moved since the job was created); job is SUPERSEDED. */
        SUPERSEDED,
        /** EMBEDDING candidate would change the active vector space; job is REINDEX_REQUIRED, row untouched. */
        REINDEX_REQUIRED
    }

    /**
     * @param job the verification job whose candidate just measured OK — must still carry the
     *            EMBEDDING measurements ({@code embeddingDimension}/{@code embeddingFingerprint})
     *            for EMBEDDING rows; the caller (future Task 6) sets those on the job before calling.
     */
    Outcome promote(ChatModelVerification job);
}
