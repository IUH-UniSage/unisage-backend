package com.unisage.backend.service.usagelimit;

import com.unisage.backend.entity.User;
import com.unisage.backend.entity.UsageLimit;

public interface UsageLimitService {

    UsageLimit checkAndGetOrCreate(User user, String ipAddress);

    void increment(UsageLimit usageLimit);
}
