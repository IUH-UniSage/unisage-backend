package com.unisage.backend.service.usagelimit;

import com.unisage.backend.entity.User;
import com.unisage.backend.entity.UsageLimit;

public interface UsageLimitService {

    /**
     * Loads (or creates) today's usage counter for the caller and throws
     * {@code AppException(USAGE_LIMIT_EXCEEDED)} if it's already maxed out.
     * Exactly one of {@code user} / {@code ipAddress} must be non-null.
     * Returns {@code null} when the limit is disabled — nothing to increment later.
     */
    UsageLimit checkAndGetOrCreate(User user, String ipAddress);

    /** No-op when {@code usageLimit} is null (limit disabled or not applicable). */
    void increment(UsageLimit usageLimit);
}
