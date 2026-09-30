package com.unisage.backend.dto.response.internal;

import java.util.UUID;

/**
 * {@code POST /internal/usage-logs} response. {@code duplicate = true} means {@code requestId} was
 * already ingested (first-write-wins) - {@code id} is the existing row's, no new line was written.
 */
public record UsageLogIngestResponse(UUID id, boolean duplicate) {
}
