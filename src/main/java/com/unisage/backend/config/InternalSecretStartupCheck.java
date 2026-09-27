package com.unisage.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.unisage.backend.security.CidrMatcher;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/** In profile prod, fails startup instead of letting a bad /internal/** config 403 at runtime. */
@Component
@RequiredArgsConstructor
public class InternalSecretStartupCheck {

    private static final String DEFAULT_SECRET = "unisage-internal-secret-key-2026";
    private static final int MIN_SECRET_LENGTH = 32;

    private final Environment environment;

    @Value("${app.internal.secret-key}")
    private String internalSecretKey;

    @Value("${app.internal.allowed-cidrs:}")
    private String allowedCidrsRaw;

    @PostConstruct
    void validate() {
        if (!isProdProfile()) {
            return;
        }
        if (internalSecretKey == null || internalSecretKey.equals(DEFAULT_SECRET)
                || internalSecretKey.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "INTERNAL_SECRET_KEY must be set to a non-default value with at least "
                            + MIN_SECRET_LENGTH + " characters in profile prod");
        }
        if (CidrMatcher.parse(allowedCidrsRaw).isEmpty()) {
            throw new IllegalStateException(
                    "INTERNAL_ALLOWED_CIDRS must be set to a valid, non-empty CIDR list in profile prod");
        }
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}
