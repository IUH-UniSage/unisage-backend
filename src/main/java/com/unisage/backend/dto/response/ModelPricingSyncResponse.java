package com.unisage.backend.dto.response;

import java.time.OffsetDateTime;

import lombok.Builder;

/** {@code rejected} = upstream rows dropped for an out-of-range price; {@code skippedManual} = rows
 * an SA override protects from the sync. */
@Builder
public record ModelPricingSyncResponse(
    int created,
    int updated,
    int unchanged,
    int skippedManual,
    int rejected,
    OffsetDateTime syncedAt
) {}
