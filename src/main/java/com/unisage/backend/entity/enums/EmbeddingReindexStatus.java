package com.unisage.backend.entity.enums;

/**
 * States of an {@code embedding_reindex_runs} row (V37 CHECK constraint). Only Java transitions a
 * run; unisage-agent reacts to the claim/progress/result responses.
 */
public enum EmbeddingReindexStatus {
    /** Waiting for unisage-agent to claim it. */
    QUEUED,
    /** Claimed by unisage-agent; holds a lease renewed on every progress report. */
    RUNNING,
    /** Every point re-embedded and cut over: the target collection is live. Terminal. */
    SUCCEEDED,
    /** The agent gave up, or the cutover checks failed. The live collection is unchanged. Terminal. */
    FAILED,
    /** Cancelled by an admin. The live collection is unchanged. Terminal. */
    CANCELLED;

    public boolean isOpen() {
        return this == QUEUED || this == RUNNING;
    }
}
