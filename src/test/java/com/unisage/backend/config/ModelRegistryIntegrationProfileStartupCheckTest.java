package com.unisage.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Spring context startup (not a direct method call) for
 * {@link ModelRegistryIntegrationProfileStartupCheck} — same pattern as
 * {@link InternalSecretStartupCheckContextTest}: {@code @PostConstruct} runs as part of context
 * refresh, so a bad profile combination must fail the context itself.
 */
class ModelRegistryIntegrationProfileStartupCheckTest {

    @Configuration
    static class Config {
        @Bean
        ModelRegistryIntegrationProfileStartupCheck check(Environment environment) {
            return new ModelRegistryIntegrationProfileStartupCheck(environment);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void integrationAlone_contextStartsSuccessfully() {
        runner.withPropertyValues("spring.profiles.active=integration")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void integrationWithTest_contextStartsSuccessfully() {
        runner.withPropertyValues("spring.profiles.active=integration,test")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void integrationWithProd_contextFailsToStart() {
        runner.withPropertyValues("spring.profiles.active=integration,prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause().hasMessageContaining("integration");
                });
    }

    @Test
    void integrationWithDev_contextFailsToStart() {
        runner.withPropertyValues("spring.profiles.active=integration,dev")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void noIntegrationProfile_prodAlone_contextStartsSuccessfully() {
        // Nothing to guard here — this check only ever fires when integration is active.
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
