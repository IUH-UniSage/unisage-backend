package com.unisage.backend.entity.enums;

import java.time.Duration;

import lombok.Getter;

/** The two anchored usage windows. A window opens at the first counted request and lasts {@code duration}. */
@Getter
public enum UsageLimitWindow {
    DAILY(Duration.ofHours(24)),
    WEEKLY(Duration.ofDays(7));

    private final Duration duration;

    UsageLimitWindow(Duration duration) {
        this.duration = duration;
    }
}
