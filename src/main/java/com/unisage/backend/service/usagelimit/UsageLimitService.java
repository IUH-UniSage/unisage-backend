package com.unisage.backend.service.usagelimit;

import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.UsageLimit;

public interface UsageLimitService {

    /** Exactly one of {@code user}/{@code guestSession} must be non-null. */
    UsageLimit checkAndGetOrCreate(User user, GuestSession guestSession);

    void increment(UsageLimit usageLimit);
}
