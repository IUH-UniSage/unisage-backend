package com.unisage.backend.dto.response.internal;

import com.unisage.backend.entity.enums.EmbeddingReindexStatus;

/** Reply to progress/result — the agent stops (and drops the target collection) on {@code CANCELLED}. */
public record InternalEmbeddingReindexStatusResponse(EmbeddingReindexStatus status) {}
