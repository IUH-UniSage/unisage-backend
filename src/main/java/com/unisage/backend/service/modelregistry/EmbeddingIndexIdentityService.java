package com.unisage.backend.service.modelregistry;

import java.util.Optional;

import com.unisage.backend.dto.request.internal.InternalEmbeddingIndexIdentityRequest;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;

public interface EmbeddingIndexIdentityService {

    Optional<InternalEmbeddingIndexIdentityResponse> getIdentity(String collectionName);

    /** @throws com.unisage.backend.exception.AppException(EMBEDDING_INDEX_IDENTITY_EXISTS) if one already exists. */
    InternalEmbeddingIndexIdentityResponse putIdentity(String collectionName, InternalEmbeddingIndexIdentityRequest request);
}
