package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;

import com.unisage.backend.entity.BaseEntity;

/**
 * Messages are ordered by {@code created_at} only, and {@code created_at} is stamped by JPA
 * auditing at persist time (never by hand, ADR 0002). To keep two rows saved back-to-back in
 * order, spin until the clock is past the previous row's stamp.
 */
final class CreatedAtSpacing {

    // created_at is rounded to microseconds by Postgres; 2us keeps the next row strictly after.
    static final long GAP_NANOS = 2_000;

    private CreatedAtSpacing() {}

    static void waitPast(BaseEntity saved) {
        LocalDateTime createdAt = saved.getCreatedAt();
        if (createdAt == null) {
            return;
        }
        LocalDateTime threshold = createdAt.plusNanos(GAP_NANOS);
        while (!LocalDateTime.now().isAfter(threshold)) {
            Thread.onSpinWait();
        }
    }
}
