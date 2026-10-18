package com.unisage.backend.service.embeddingindexpointer;

import java.util.Optional;

import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;

/**
 * Resolves the live Qdrant collection of this environment's base collection
 * ({@code model-registry.embedding-collection-name}) — changes/17-10-2026-Embedding-Reindex/plan.md D1.
 */
public interface EmbeddingIndexPointerService {

    /** The configured base collection, e.g. {@code unisage_chunks}. */
    String baseCollection();

    /** The collection retrieval and ingestion use: the pointer's target, or the base collection. */
    String activeCollection();

    /** Identity of {@link #activeCollection()}, if one is established. */
    Optional<InternalEmbeddingIndexIdentityResponse> activeIdentity();

    /** Moves the live collection — only a re-index cutover calls this. */
    void pointTo(String activeCollection);
}
