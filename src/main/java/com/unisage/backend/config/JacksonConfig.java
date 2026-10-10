package com.unisage.backend.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link JsonNullableModule} so {@code JsonNullable<T>} fields (used by
 * {@code ChatModelUpdateRequest.apiKey} to distinguish "field absent" from "field explicitly
 * null" — plan.md "Credential rotation") (de)serialize correctly. Spring Boot's Jackson
 * autoconfiguration picks up every {@code com.fasterxml.jackson.databind.Module} bean found in
 * the context, so declaring this bean is enough — no manual {@code ObjectMapper} wiring needed.
 */
@Configuration
public class JacksonConfig {

    /** ISO local date-time plus a literal {@code Z}: "2026-10-05T06:02:00Z". */
    static final DateTimeFormatter UTC_LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendLiteral('Z')
            .toFormatter();

    /**
     * Every {@code LocalDateTime} here holds UTC (see {@code UnisageBackendApplication#main}), but
     * Jackson writes it without an offset - and a browser parses an offset-less ISO string as its
     * own local time, shifting every timestamp by the viewer's UTC offset. Writing the {@code Z}
     * makes the instant unambiguous. Incoming values with a trailing {@code Z} still deserialize:
     * jsr310's default {@code LocalDateTime} deserializer is lenient about it.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcLocalDateTimeSerializer() {
        return builder -> builder.serializerByType(
                LocalDateTime.class, new LocalDateTimeSerializer(UTC_LOCAL_DATE_TIME));
    }

    @Bean
    public JsonNullableModule jsonNullableModule() {
        return new JsonNullableModule();
    }
}
