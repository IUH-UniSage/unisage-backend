package com.unisage.backend.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.entity.ChatModel;

/** Which identity fields of an EMBEDDING {@link ChatModel} differ from an established index identity. */
public final class EmbeddingIdentityMismatches {

    private EmbeddingIdentityMismatches() {}

    /** Empty when the model's vectors are interchangeable with the index's. */
    public static List<String> of(ChatModel model, InternalEmbeddingIndexIdentityResponse identity) {
        List<String> mismatches = new ArrayList<>();
        if (!Objects.equals(model.getLlmProvider(), identity.provider())) {
            mismatches.add("provider");
        }
        if (!Objects.equals(model.getLlmModelName(), identity.modelName())) {
            mismatches.add("modelName");
        }
        if (!Objects.equals(model.getModelSourceRef(), identity.modelSourceRef())) {
            mismatches.add("modelSourceRef");
        }
        if (!Objects.equals(model.getApiBaseUrl(), identity.apiBaseUrl())) {
            mismatches.add("apiBaseUrl");
        }
        if (!Objects.equals(model.getEmbeddingDimension(), identity.dimension())) {
            mismatches.add("dimension");
        }
        if (!EmbeddingFingerprintMatcher.matches(model.getEmbeddingFingerprint(), identity.fingerprint())) {
            mismatches.add("fingerprint");
        }
        return mismatches;
    }
}
