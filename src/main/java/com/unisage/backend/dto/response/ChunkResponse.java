package com.unisage.backend.dto.response;

import java.util.List;

public record ChunkResponse(
    List<String> chunks,
    int totalChunks
) {}
