package com.unisage.backend.dto.response;

import java.time.OffsetDateTime;

import com.unisage.backend.entity.enums.UsageWindowStatus;

import lombok.Builder;

/**
 * One usage window as the caller sees it. Deliberately carries no token counts: the client only ever
 * shows a percentage and a reset time.
 *
 * <p>{@code UNLIMITED}: both nullable fields are null. {@code IDLE}: 100% left, no reset time.
 * {@code ACTIVE}: 0..100% left and the time the window ends.
 */
@Builder
public record UsageWindowResponse(
        UsageWindowStatus status,
        Integer remainingPercent,
        OffsetDateTime resetAt
) {}
