package com.unisage.backend.dto.request.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /internal/message-traces} body - the retrieval trace of one assistant message, sent
 * once per turn by unisage-agent after its finalize PATCH. The 256 KB limit is checked in the
 * service on the serialized JSON.
 */
public record MessageTraceIngestRequest(
    @NotNull(message = "messageId không được để trống")
    UUID messageId,

    @Size(max = 100, message = "traceId không được vượt quá 100 ký tự")
    String traceId,

    @Size(max = 50, message = "chunks không được vượt quá 50 phần tử")
    List<@NotNull Map<String, Object>> chunks,

    @Size(max = 50, message = "webSources không được vượt quá 50 phần tử")
    List<@NotNull Map<String, Object>> webSources
) {}
