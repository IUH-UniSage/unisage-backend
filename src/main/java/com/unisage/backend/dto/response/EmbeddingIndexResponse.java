package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

/** {@code GET /chat-models/embedding-index} — what the live index holds and its re-index runs. */
@Builder
public record EmbeddingIndexResponse(
    String baseCollection,
    String activeCollection,
    /** Null until the first embed into {@link #activeCollection} establishes it. */
    ActiveIdentity activeIdentity,
    /** The ACTIVE EMBEDDING model, or null. */
    ActiveModel activeModel,
    /** The QUEUED/RUNNING run, or null. */
    EmbeddingReindexRunResponse currentRun,
    /** The most recent run in any state, or null. */
    EmbeddingReindexRunResponse lastRun
) {

    public record ActiveIdentity(
        String provider,
        String modelName,
        Integer dimension,
        LocalDateTime establishedAt
    ) {}

    public record ActiveModel(
        UUID id,
        String displayName,
        String llmProvider,
        String llmModelName
    ) {}
}
