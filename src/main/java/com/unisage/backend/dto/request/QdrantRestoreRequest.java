package com.unisage.backend.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Restores the collection a QDRANT_SNAPSHOT run was taken of, from that snapshot. */
public record QdrantRestoreRequest(@NotNull UUID backupRunId) {
}
