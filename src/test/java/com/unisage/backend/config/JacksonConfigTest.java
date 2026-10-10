package com.unisage.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Runs the real Jackson autoconfiguration, so the serializer must win over jsr310's default. */
class JacksonConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    @Test
    void localDateTimeIsWrittenAsUtcWithZ() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThat(mapper.writeValueAsString(LocalDateTime.of(2026, 10, 5, 6, 2)))
                    .isEqualTo("\"2026-10-05T06:02:00Z\"");
            assertThat(mapper.writeValueAsString(LocalDateTime.of(2026, 10, 5, 6, 2, 3, 120_000_000)))
                    .isEqualTo("\"2026-10-05T06:02:03.12Z\"");
        });
    }

    @Test
    void localDateTimeWithZStillDeserializes() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThat(mapper.readValue("\"2026-10-05T06:02:00Z\"", LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2026, 10, 5, 6, 2));
            assertThat(mapper.readValue("\"2026-10-05T06:02:00\"", LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2026, 10, 5, 6, 2));
        });
    }
}
