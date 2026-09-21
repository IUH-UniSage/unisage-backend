package com.unisage.backend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.unisage.backend.repository.UsageLimitPlanRepository;

import lombok.RequiredArgsConstructor;

/**
 * Guests and roles without a plan resolve to the default usage limit plan, so exactly one must
 * exist. The database forbids two (partial unique index); this stops the application from starting
 * with none, instead of letting chat silently run without a limit.
 */
@Component
@RequiredArgsConstructor
public class UsageLimitPlanInvariantCheck implements ApplicationRunner {

    private final UsageLimitPlanRepository usageLimitPlanRepository;

    @Override
    public void run(ApplicationArguments args) {
        long defaults = usageLimitPlanRepository.countByIsDefaultTrue();
        if (defaults != 1) {
            throw new IllegalStateException(
                    "Expected exactly one default usage limit plan but found " + defaults
                            + ". Restore it before starting the application.");
        }
    }
}
