package com.unisage.backend.dto.request;

import java.util.List;

public record EmbedRequest(
    String documentId,
    String userId,
    List<String> chunks
) {}
