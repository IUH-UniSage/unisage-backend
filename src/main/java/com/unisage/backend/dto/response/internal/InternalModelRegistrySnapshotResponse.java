package com.unisage.backend.dto.response.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;

/**
 * {@code GET /internal/model-registry/snapshot} response — plan.md "Internal API contract"
 * endpoint #1, the one and only source of provider credentials for {@code unisage-agent}. Every
 * {@link CredentialEntry#apiKey()} is plaintext, decrypted via {@code ApiKeyConverter} — this DTO
 * (and the collection it is nested in) must never be logged with a naive {@code toString()}, hence
 * the redacted overrides below. Serialization (Jackson, for the actual HTTP response) uses the
 * record accessors directly and is unaffected by {@code toString()}.
 */
public record InternalModelRegistrySnapshotResponse(
    long version,
    OffsetDateTime generatedAt,
    Map<ChatModelPurpose, List<CredentialEntry>> purposes,
    InternalEmbeddingIndexIdentityResponse embeddingIndexIdentity
) {

    @Override
    public String toString() {
        return "InternalModelRegistrySnapshotResponse[version=" + version
                + ", generatedAt=" + generatedAt
                + ", purposes=" + redactedPurposeCounts()
                + ", embeddingIndexIdentity=" + embeddingIndexIdentity + "]";
    }

    private String redactedPurposeCounts() {
        // Never toString() the credential list itself (even indirectly via a collection's default
        // toString) — only report how many entries per purpose.
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<ChatModelPurpose, List<CredentialEntry>> entry : purposes.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append("=").append(entry.getValue().size()).append(" credential(s)");
        }
        return sb.append("}").toString();
    }

    /** One ACTIVE credential row — field order/names follow plan.md's snapshot JSON example exactly. */
    public record CredentialEntry(
        UUID id,
        int revision,
        ChatModelSourceType sourceType,
        String provider,
        String modelName,
        String apiBaseUrl,
        String apiKey,
        Integer priority,
        Integer maxRpm
    ) {
        @Override
        public String toString() {
            return "CredentialEntry[id=" + id
                    + ", revision=" + revision
                    + ", sourceType=" + sourceType
                    + ", provider=" + provider
                    + ", modelName=" + modelName
                    + ", apiBaseUrl=" + apiBaseUrl
                    + ", apiKey=REDACTED, priority=" + priority
                    + ", maxRpm=" + maxRpm + "]";
        }
    }
}
