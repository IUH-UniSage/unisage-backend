package com.unisage.backend.dto.ingestion;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentIngestionResponse(
        UUID documentId,
        String status,
        int chunkCount,
        String embeddingModel
) {
}
