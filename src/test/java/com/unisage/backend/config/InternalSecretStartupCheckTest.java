package com.unisage.backend.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalSecretStartupCheckTest {

    private static final String VALID_SECRET = "a-real-secret-at-least-32-characters-long";
    private static final String DEFAULT_SECRET = "unisage-internal-secret-key-2026";

    private Environment environment;

    @BeforeEach
    void setUp() {
        environment = mock(Environment.class);
    }

    private InternalSecretStartupCheck checkFor(String... activeProfiles) {
        when(environment.getActiveProfiles()).thenReturn(activeProfiles);
        InternalSecretStartupCheck check = new InternalSecretStartupCheck(environment);
        ReflectionTestUtils.setField(check, "internalSecretKey", VALID_SECRET);
        ReflectionTestUtils.setField(check, "allowedCidrsRaw", "127.0.0.1/32");
        return check;
    }

    @Test
    void nonProdProfile_defaultSecret_neverFails() {
        InternalSecretStartupCheck check = checkFor("dev");
        ReflectionTestUtils.setField(check, "internalSecretKey", DEFAULT_SECRET);
        ReflectionTestUtils.setField(check, "allowedCidrsRaw", "");

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(check, "validate")).doesNotThrowAnyException();
    }

    @Test
    void prodProfile_defaultSecret_fails() {
        InternalSecretStartupCheck check = checkFor("prod");
        ReflectionTestUtils.setField(check, "internalSecretKey", DEFAULT_SECRET);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(check, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_SECRET_KEY");
    }

    @Test
    void prodProfile_shortSecret_fails() {
        InternalSecretStartupCheck check = checkFor("prod");
        ReflectionTestUtils.setField(check, "internalSecretKey", "too-short");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(check, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_SECRET_KEY");
    }

    @Test
    void prodProfile_emptyCidr_fails() {
        InternalSecretStartupCheck check = checkFor("prod");
        ReflectionTestUtils.setField(check, "allowedCidrsRaw", "");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(check, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_ALLOWED_CIDRS");
    }

    @Test
    void prodProfile_unparsableCidr_fails() {
        InternalSecretStartupCheck check = checkFor("prod");
        ReflectionTestUtils.setField(check, "allowedCidrsRaw", "10.0.0.0/33");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(check, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_ALLOWED_CIDRS");
    }

    @Test
    void prodProfile_validConfig_passes() {
        InternalSecretStartupCheck check = checkFor("prod");

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(check, "validate")).doesNotThrowAnyException();
    }
}
