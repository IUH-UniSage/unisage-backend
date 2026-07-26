package com.unisage.backend.dto.request;

public record ChunkRequest(
    String content,
    int chunkSize,
    int chunkOverlap
) {}
