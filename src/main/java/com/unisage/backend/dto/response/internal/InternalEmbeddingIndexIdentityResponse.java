package com.unisage.backend.dto.response.internal;

import java.time.LocalDateTime;

/**
 * {@code GET}/{@code PUT .../embedding-index/{collection}/identity} response — plan.md "Internal
 * API contract" endpoints #6/#7. No secret, so unlike snapshot/claim this one is fine to log —
 * still served with {@code no-store} like every {@code /internal/**} response, for consistency.
 */
public record InternalEmbeddingIndexIdentityResponse(
    String collectionName,
    String provider,
    String modelName,
    String modelSourceRef,
    String apiBaseUrl,
    Integer dimension,
    Float[] fingerprint,
    LocalDateTime establishedAt,
    String establishedBy
) {}
