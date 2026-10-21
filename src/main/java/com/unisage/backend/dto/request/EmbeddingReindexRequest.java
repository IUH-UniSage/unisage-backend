package com.unisage.backend.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** {@code POST /chat-models/embedding-index/reindex} body — the EMBEDDING model to re-index into. */
public record EmbeddingReindexRequest(
    @NotNull(message = "chatModelId không được để trống")
    UUID chatModelId
) {}
