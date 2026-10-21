package com.unisage.backend.service.embeddingindexpointer;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.entity.EmbeddingIndexPointer;
import com.unisage.backend.repository.EmbeddingIndexPointerRepository;
import com.unisage.backend.service.embeddingindexidentity.EmbeddingIndexIdentityService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmbeddingIndexPointerServiceImpl implements EmbeddingIndexPointerService {

    private final EmbeddingIndexPointerRepository pointerRepository;
    private final EmbeddingIndexIdentityService embeddingIndexIdentityService;

    @Value("${model-registry.embedding-collection-name:unisage_chunks}")
    private String baseCollection;

    @Override
    public String baseCollection() {
        return baseCollection;
    }

    @Override
    @Transactional(readOnly = true)
    public String activeCollection() {
        return pointerRepository.findById(baseCollection)
                .map(EmbeddingIndexPointer::getActiveCollection)
                .orElse(baseCollection);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InternalEmbeddingIndexIdentityResponse> activeIdentity() {
        return embeddingIndexIdentityService.getIdentity(activeCollection());
    }

    @Override
    @Transactional
    public void pointTo(String activeCollection) {
        pointerRepository.upsert(baseCollection, activeCollection);
    }
}
