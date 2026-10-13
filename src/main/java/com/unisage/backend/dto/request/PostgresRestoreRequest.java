package com.unisage.backend.dto.request;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Exactly one of the two: {@code backupRunId} restores that Full or Differential as it was when
 * taken; {@code targetTime} (UTC, e.g. "2026-10-13T03:14:00Z") replays the transaction log up to
 * that moment from the newest backup before it.
 */
public record PostgresRestoreRequest(UUID backupRunId, LocalDateTime targetTime) {
}
