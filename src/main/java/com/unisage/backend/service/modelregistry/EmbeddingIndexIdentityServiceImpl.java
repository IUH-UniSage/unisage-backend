package com.unisage.backend.service.modelregistry;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.request.internal.InternalEmbeddingIndexIdentityRequest;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.entity.EmbeddingIndexIdentity;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.EmbeddingIndexIdentityRepository;
import com.unisage.backend.repository.EmbeddingIndexIdentityWriter;

import lombok.RequiredArgsConstructor;

/**
 * Backs endpoints #6/#7 of plan.md "Internal API contract". The PUT path is deliberately a single
 * {@code INSERT ... ON CONFLICT DO NOTHING} (see {@link EmbeddingIndexIdentityWriter}) — never a
 * read-then-write — so two concurrent first-upserts can never both "win" (plan.md "Embedding
 * identity guard", "Race khởi tạo lần đầu").
 */
@Service
@RequiredArgsConstructor
public class EmbeddingIndexIdentityServiceImpl implements EmbeddingIndexIdentityService {

    private final EmbeddingIndexIdentityRepository repository;
    private final EmbeddingIndexIdentityWriter writer;

    @Override
    @Transactional(readOnly = true)
    public Optional<InternalEmbeddingIndexIdentityResponse> getIdentity(String collectionName) {
        return repository.findByCollectionName(collectionName).map(this::toResponse);
    }

    @Override
    @Transactional
    public InternalEmbeddingIndexIdentityResponse putIdentity(String collectionName, InternalEmbeddingIndexIdentityRequest request) {
        EmbeddingIndexIdentity candidate = EmbeddingIndexIdentity.builder()
                .collectionName(collectionName)
                .provider(request.provider())
                .modelName(request.modelName())
                .modelSourceRef(request.modelSourceRef())
                .apiBaseUrl(request.apiBaseUrl())
                .dimension(request.dimension())
                .fingerprint(request.fingerprint())
                .establishedBy(request.establishedBy())
                .build();

        boolean inserted = writer.insertIfAbsent(candidate);
        if (!inserted) {
            throw new AppException(ErrorCode.EMBEDDING_INDEX_IDENTITY_EXISTS);
        }

        // Re-read rather than echo the request back — establishedAt (and any DB-side normalization)
        // must reflect what was actually committed, not what the caller sent.
        return repository.findByCollectionName(collectionName)
                .map(this::toResponse)
                .orElseThrow(() -> new AppException(ErrorCode.SYS_UNCATEGORIZED));
    }

    private InternalEmbeddingIndexIdentityResponse toResponse(EmbeddingIndexIdentity identity) {
        return new InternalEmbeddingIndexIdentityResponse(
                identity.getCollectionName(),
                identity.getProvider(),
                identity.getModelName(),
                identity.getModelSourceRef(),
                identity.getApiBaseUrl(),
                identity.getDimension(),
                identity.getFingerprint(),
                identity.getEstablishedAt(),
                identity.getEstablishedBy());
    }
}
