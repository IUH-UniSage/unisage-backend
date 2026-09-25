package com.unisage.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Spring context startup — not a direct method call — for {@link InternalSecretStartupCheck}.
 * {@code @PostConstruct} runs as part of context refresh, so a bad prod config must fail the
 * context itself, matching how this would actually surface in a real deployment.
 */
class InternalSecretStartupCheckContextTest {

    private static final String VALID_SECRET = "a-real-secret-at-least-32-characters-long";
    private static final String DEFAULT_SECRET = "unisage-internal-secret-key-2026";

    @Configuration
    static class Config {
        @Bean
        InternalSecretStartupCheck internalSecretStartupCheck(Environment environment) {
            return new InternalSecretStartupCheck(environment);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void prod_cidrNotSet_contextFailsToStart() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.internal.secret-key=" + VALID_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause().hasMessageContaining("INTERNAL_ALLOWED_CIDRS");
                });
    }

    @Test
    void prod_cidrEmpty_contextFailsToStart() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.internal.secret-key=" + VALID_SECRET,
                        "app.internal.allowed-cidrs=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause().hasMessageContaining("INTERNAL_ALLOWED_CIDRS");
                });
    }

    @Test
    void prod_cidrUnparsable_outOfRangePrefix_contextFailsToStart() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.internal.secret-key=" + VALID_SECRET,
                        "app.internal.allowed-cidrs=10.0.0.0/33")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause().hasMessageContaining("INTERNAL_ALLOWED_CIDRS");
                });
    }

    @Test
    void prod_cidrUnparsable_garbage_contextFailsToStart() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.internal.secret-key=" + VALID_SECRET,
                        "app.internal.allowed-cidrs=abc")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause().hasMessageContaining("INTERNAL_ALLOWED_CIDRS");
                });
    }

    @Test
    void prod_validConfig_contextStartsSuccessfully() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.internal.secret-key=" + VALID_SECRET,
                        "app.internal.allowed-cidrs=127.0.0.1/32")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prod_defaultSecret_contextFailsToStart() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.internal.secret-key=" + DEFAULT_SECRET,
                        "app.internal.allowed-cidrs=127.0.0.1/32")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause().hasMessageContaining("INTERNAL_SECRET_KEY");
                });
    }

    @Test
    void dev_cidrEmpty_contextStillStartsSuccessfully() {
        // Fail-closed happens per-request in InternalCallerCidrFilter, not at startup, outside prod.
        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "app.internal.secret-key=" + DEFAULT_SECRET,
                        "app.internal.allowed-cidrs=")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
