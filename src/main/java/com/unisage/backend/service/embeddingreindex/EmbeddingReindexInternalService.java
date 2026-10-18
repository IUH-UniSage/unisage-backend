package com.unisage.backend.service.embeddingreindex;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.internal.InternalEmbeddingReindexProgressRequest;
import com.unisage.backend.dto.request.internal.InternalEmbeddingReindexResultRequest;
import com.unisage.backend.dto.response.internal.InternalEmbeddingReindexClaimResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingReindexStatusResponse;

/** unisage-agent side of an embedding re-index — changes/17-10-2026-Embedding-Reindex/plan.md §2.3. */
public interface EmbeddingReindexInternalService {

    List<InternalEmbeddingReindexClaimResponse> claim(int limit);

    InternalEmbeddingReindexStatusResponse progress(UUID runId, InternalEmbeddingReindexProgressRequest request);

    /** Ends the run; a success cuts over to the target collection and activates the target model. */
    InternalEmbeddingReindexStatusResponse result(UUID runId, InternalEmbeddingReindexResultRequest request);
}
