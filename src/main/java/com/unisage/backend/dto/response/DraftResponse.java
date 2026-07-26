package com.unisage.backend.dto.response;

public record DraftResponse(
    String filename,
    String rawContent,
    long charCount
) {}
