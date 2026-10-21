package com.unisage.backend.dto.request;

import java.util.UUID;

/**
 * {@code POST /chat-models/embedding-index/reindex} body.
 *
 * <p>{@code rebuild=false} (default): re-embed into the EMBEDDING model {@code chatModelId}.
 * {@code rebuild=true}: rebuild the live collection with the model that is already ACTIVE - the
 * dense vectors are copied as they are and only the {@code bm25} sparse vector is added
 * (unisage-agent docs/specs/SPEC-hybrid-retrieval.md); {@code chatModelId} is then ignored.
 */
public record EmbeddingReindexRequest(
    UUID chatModelId,
    Boolean rebuild
) {
    public boolean isRebuild() {
        return Boolean.TRUE.equals(rebuild);
    }
}
