package com.unisage.backend.service.embeddingreindex;

import java.util.UUID;

import com.unisage.backend.dto.request.EmbeddingReindexRequest;
import com.unisage.backend.dto.response.EmbeddingIndexResponse;
import com.unisage.backend.dto.response.EmbeddingReindexRunResponse;

/** Admin side of an embedding re-index — changes/17-10-2026-Embedding-Reindex/plan.md §2.2. */
public interface EmbeddingReindexService {

    EmbeddingIndexResponse getIndex();

    /** Queues a re-index of the live collection into a new one embedded by {@code request.chatModelId()}. */
    EmbeddingReindexRunResponse start(EmbeddingReindexRequest request);

    /** Cancels a QUEUED/RUNNING run; the agent drops the target collection on its next progress report. */
    EmbeddingReindexRunResponse cancel(UUID runId);
}
