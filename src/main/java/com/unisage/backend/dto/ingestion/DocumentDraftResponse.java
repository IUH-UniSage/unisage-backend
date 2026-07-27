package com.unisage.backend.dto.ingestion;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentDraftResponse(
        UUID documentId,
        String source,
        String strategy,
        Map<String, Object> metadata,
        int totalChunks,
        List<DocumentChunkDraft> chunks
) {
}
