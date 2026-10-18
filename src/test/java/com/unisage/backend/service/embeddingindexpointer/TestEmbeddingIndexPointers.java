package com.unisage.backend.service.embeddingindexpointer;

import static org.mockito.Mockito.mock;

import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.repository.EmbeddingIndexPointerRepository;
import com.unisage.backend.service.embeddingindexidentity.EmbeddingIndexIdentityService;

/** A real pointer service with no pointer row: {@code baseCollection} is live, identities come from {@code identityService}. */
public final class TestEmbeddingIndexPointers {

    private TestEmbeddingIndexPointers() {}

    public static EmbeddingIndexPointerServiceImpl over(EmbeddingIndexIdentityService identityService, String baseCollection) {
        EmbeddingIndexPointerServiceImpl pointer =
                new EmbeddingIndexPointerServiceImpl(mock(EmbeddingIndexPointerRepository.class), identityService);
        ReflectionTestUtils.setField(pointer, "baseCollection", baseCollection);
        return pointer;
    }
}
