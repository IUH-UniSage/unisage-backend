package com.unisage.backend.config;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * Safety rail for {@link ModelRegistryIntegrationSeeder} and the {@code /internal/test/registry/reset}
 * endpoint (todo.md Task 0.5): both are {@code @Profile("integration")}-gated, but a profile gate
 * alone only stops them from being *registered* in the wrong environment — it does nothing if
 * {@code integration} is accidentally active *alongside* a real one (e.g. a copy-pasted
 * {@code SPRING_PROFILES_ACTIVE=prod,integration}). Fail startup outright in that case, the same
 * way {@link InternalSecretStartupCheck} fails startup instead of letting a bad {@code /internal/**}
 * config 403 at runtime.
 */
@Component
@RequiredArgsConstructor
public class ModelRegistryIntegrationProfileStartupCheck {

    private static final String INTEGRATION_PROFILE = "integration";
    private static final String TEST_PROFILE = "test";

    private final Environment environment;

    @PostConstruct
    void validate() {
        List<String> activeProfiles = List.of(environment.getActiveProfiles());
        if (!activeProfiles.contains(INTEGRATION_PROFILE)) {
            return;
        }

        List<String> disallowed = activeProfiles.stream()
                .filter(profile -> !INTEGRATION_PROFILE.equals(profile) && !TEST_PROFILE.equals(profile))
                .collect(Collectors.toList());

        if (!disallowed.isEmpty()) {
            throw new IllegalStateException(
                    "Profile 'integration' must never be active together with any profile other than "
                            + "'test' — found disallowed profile(s): " + disallowed
                            + ". This profile enables ModelRegistryIntegrationSeeder and "
                            + "POST /internal/test/registry/reset, which must never run outside the "
                            + "cross-repo test harness.");
        }
    }
}
