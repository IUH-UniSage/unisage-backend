package com.unisage.backend.dto.response.internal;

import java.util.UUID;

import lombok.Builder;

/** Result of {@code POST /internal/message-traces}: what is now stored for the message. */
@Builder
public record MessageTraceIngestResponse(
    UUID messageId,
    String traceId,
    int chunkCount,
    int webSourceCount
) {}
