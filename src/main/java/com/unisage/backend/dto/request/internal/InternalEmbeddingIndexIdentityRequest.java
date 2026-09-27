package com.unisage.backend.dto.request.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code PUT /internal/model-registry/embedding-index/{collection}/identity} body — plan.md
 * "Internal API contract" endpoint #7. Sent once, either by the bootstrap CLI (reading the old
 * {@code .env} config, {@code establishedBy = "bootstrap-cli"}) or by the first credential upserted
 * into an empty collection ({@code establishedBy = "first-upsert"}).
 */
public record InternalEmbeddingIndexIdentityRequest(
    @NotBlank(message = "provider không được để trống")
    String provider,

    @NotBlank(message = "modelName không được để trống")
    String modelName,

    String modelSourceRef,

    String apiBaseUrl,

    @NotNull(message = "dimension không được để trống")
    @Positive(message = "dimension phải lớn hơn 0")
    Integer dimension,

    @NotEmpty(message = "fingerprint không được để trống")
    Float[] fingerprint,

    @NotBlank(message = "establishedBy không được để trống")
    String establishedBy
) {}
