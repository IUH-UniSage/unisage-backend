package com.unisage.backend.dto.ingestion;

public record DocumentIngestionOptions(
        String faculty,
        int minAccessLevel,
        int version,
        String strategy,
        int chunkSize,
        int chunkOverlap,
        double semanticThreshold,
        int rowsPerChunk,
        boolean includeHeader,
        String metadataJson
) {
}
